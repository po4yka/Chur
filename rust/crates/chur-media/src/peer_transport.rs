//! Ephemeral, authenticated, direct device connections. No relay or durable service.
use chur_core::{ChurStatus, Error, Result, ensure};
use chur_crypto::{Key, Nonce, aead, random};
use std::io::{Read, Write};
use std::net::{IpAddr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::time::{Duration, Instant};
use zeroize::Zeroizing;

/// Maximum plaintext in one bounded peer frame.
pub const FRAME_MAX: usize = 4 * 1024 * 1024;
const HELLO_LEN: usize = 40;
const MAGIC: &[u8; 8] = b"CHURP2P1";
/// An unproven connection can send only its hello and one small first frame.
const CANDIDATE_FRAME_MAX: usize = 64 * 1024;
/// Unproven connections that one invitation examines at the same time.
const CANDIDATES_MAX: usize = 4;
/// Failed connections after which an invitation closes.
const ATTEMPTS_MAX: u32 = 32;
const CANDIDATE_TIMEOUT: Duration = Duration::from_secs(10);
const IDLE_TIMEOUT: Duration = Duration::from_secs(120);

/// One foreground-only invitation or connected peer. Secrets never enter durable storage.
pub struct Peer {
    listener: Option<TcpListener>,
    candidates: Vec<Channel>,
    channel: Option<Channel>,
    secret: Key,
    initiator: bool,
    deadline: Instant,
    failures: u32,
}
impl Peer {
    /// Opens a one-use invitation on a random port of one device interface address.
    /// The listener never binds all interfaces.
    pub fn listen(address: IpAddr) -> Result<(Self, u16, Key)> {
        ensure!(
            !address.is_unspecified() && !address.is_multicast(),
            InvalidInput,
            "invalid peer address"
        );
        let listener = TcpListener::bind((address, 0)).map_err(network)?;
        listener.set_nonblocking(true).map_err(network)?;
        let port = listener.local_addr().map_err(network)?.port();
        let secret = Key::new(random::array()?);
        let mut peer = Self::new(secret.duplicate(), false);
        peer.listener = Some(listener);
        Ok((peer, port, secret))
    }
    /// Connects directly to a literal device IP address, without DNS, discovery, or relays.
    pub fn connect(address: IpAddr, port: u16, secret: Key) -> Result<Self> {
        ensure!(
            port != 0 && !address.is_unspecified() && !address.is_multicast(),
            InvalidInput,
            "invalid peer address"
        );
        let stream =
            TcpStream::connect_timeout(&SocketAddr::new(address, port), Duration::from_secs(3))
                .map_err(network)?;
        let mut peer = Self::new(secret, true);
        peer.channel = Some(Channel::create(stream, FRAME_MAX, IDLE_TIMEOUT)?);
        Ok(peer)
    }
    fn new(secret: Key, initiator: bool) -> Self {
        Self {
            listener: None,
            candidates: Vec::new(),
            channel: None,
            secret,
            initiator,
            deadline: Instant::now() + IDLE_TIMEOUT,
            failures: 0,
        }
    }
    /// Pumps bounded nonblocking IO; true means the two fresh handshake challenges arrived
    /// and, on the listener, the connector proved the invitation secret.
    pub fn poll(&mut self) -> Result<bool> {
        if self.channel.is_none() {
            self.accept()?;
        }
        let Some(channel) = self.channel.as_mut() else {
            return Ok(false);
        };
        channel.pump(true)?;
        channel.handshake(&self.secret, self.initiator)?;
        Ok(channel.tx_key.is_some() && channel.outgoing.is_empty())
    }
    /// Examines bounded unproven connections until one opens its first frame with the
    /// invitation keys. A failed connection is closed and the invitation stays open.
    fn accept(&mut self) -> Result<()> {
        ensure!(
            Instant::now() < self.deadline,
            NetworkFailure,
            "peer invitation expired"
        );
        let listener = self
            .listener
            .as_ref()
            .ok_or_else(|| Error::new(ChurStatus::NetworkFailure, "peer disconnected"))?;
        let mut accepted = Vec::new();
        while self.candidates.len() + accepted.len() < CANDIDATES_MAX {
            match listener.accept() {
                Ok((stream, _)) => accepted.push(stream),
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(e) => return Err(network(e)),
            }
        }
        for stream in accepted {
            match Channel::create(stream, CANDIDATE_FRAME_MAX, CANDIDATE_TIMEOUT) {
                Ok(candidate) => self.candidates.push(candidate),
                Err(_) => self.reject()?,
            }
        }
        let mut index = 0;
        while index < self.candidates.len() {
            match self.candidates[index].prove(&self.secret) {
                Ok(None) => index += 1,
                Ok(Some(first)) => {
                    let mut channel = self.candidates.swap_remove(index);
                    channel.first = Some(first);
                    channel.frame_max = FRAME_MAX;
                    channel.deadline = Instant::now() + IDLE_TIMEOUT;
                    self.channel = Some(channel);
                    self.candidates.clear();
                    self.listener = None;
                    return Ok(());
                }
                Err(_) => {
                    self.candidates.swap_remove(index);
                    self.reject()?;
                }
            }
        }
        Ok(())
    }
    fn reject(&mut self) -> Result<()> {
        self.failures += 1;
        ensure!(
            self.failures < ATTEMPTS_MAX,
            AuthenticationFailed,
            "too many failed peer connections"
        );
        Ok(())
    }
    /// Queues exactly one authenticated frame. Call flush before another send.
    pub fn send(&mut self, bytes: &[u8]) -> Result<()> {
        let channel = self
            .channel
            .as_mut()
            .ok_or_else(|| Error::new(ChurStatus::Conflict, "peer handshake pending"))?;
        channel.seal(bytes)
    }
    /// Flushes queued output without blocking.
    pub fn flush(&mut self) -> Result<bool> {
        self.poll()?;
        Ok(self
            .channel
            .as_ref()
            .is_some_and(|channel| channel.outgoing.is_empty()))
    }
    /// Receives a full verified frame, retaining incomplete TCP reads for the next poll.
    pub fn receive(&mut self) -> Result<Option<Zeroizing<Vec<u8>>>> {
        self.poll()?;
        let Some(channel) = self.channel.as_mut() else {
            return Ok(None);
        };
        if let Some(first) = channel.first.take() {
            return Ok(Some(first));
        }
        channel.open()
    }
}

/// One TCP stream with its handshake state and direction keys.
struct Channel {
    stream: TcpStream,
    hello: [u8; HELLO_LEN],
    tx_key: Option<Key>,
    rx_key: Option<Key>,
    tx_sequence: u64,
    rx_sequence: u64,
    incoming: Vec<u8>,
    outgoing: Zeroizing<Vec<u8>>,
    sent: usize,
    deadline: Instant,
    frame_max: usize,
    /// The verified first frame of a listener connection, kept for the caller.
    first: Option<Zeroizing<Vec<u8>>>,
}
impl Channel {
    fn create(stream: TcpStream, frame_max: usize, timeout: Duration) -> Result<Self> {
        stream.set_nonblocking(true).map_err(network)?;
        stream.set_nodelay(true).map_err(network)?;
        let mut hello = [0; HELLO_LEN];
        hello[..8].copy_from_slice(MAGIC);
        hello[8..].copy_from_slice(&random::array::<32>()?);
        let mut outgoing = Zeroizing::new(Vec::new());
        outgoing.extend_from_slice(&hello);
        Ok(Self {
            stream,
            hello,
            tx_key: None,
            rx_key: None,
            tx_sequence: 0,
            rx_sequence: 0,
            incoming: Vec::new(),
            outgoing,
            sent: 0,
            deadline: Instant::now() + timeout,
            frame_max,
            first: None,
        })
    }
    /// Advances an unproven listener connection; a frame means the secret is proven.
    fn prove(&mut self, secret: &Key) -> Result<Option<Zeroizing<Vec<u8>>>> {
        self.pump(false)?;
        self.handshake(secret, false)?;
        self.open()
    }
    /// Pumps bounded nonblocking IO. An established stream extends its idle deadline.
    fn pump(&mut self, extend: bool) -> Result<()> {
        ensure!(
            Instant::now() < self.deadline,
            NetworkFailure,
            "peer connection timed out"
        );
        // A bounded pump never holds up a vault lock waiting for a peer.
        for _ in 0..16 {
            if self.sent == self.outgoing.len() {
                break;
            }
            match self.stream.write(&self.outgoing[self.sent..]) {
                Ok(0) => return Err(Error::new(ChurStatus::NetworkFailure, "peer write ended")),
                Ok(n) => {
                    self.sent += n;
                    if extend {
                        self.deadline = Instant::now() + IDLE_TIMEOUT;
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(e) => return Err(network(e)),
            }
        }
        if self.sent == self.outgoing.len() {
            self.outgoing.clear();
            self.sent = 0;
        }
        let mut chunk = [0; 64 * 1024];
        for _ in 0..16 {
            match self.stream.read(&mut chunk) {
                Ok(0) => {
                    // A full final frame survives FIN; a truncated frame fails immediately.
                    let complete = if self.tx_key.is_none() {
                        self.incoming.len() >= HELLO_LEN
                    } else if self.incoming.len() >= 4 {
                        self.incoming.len() >= frame_length(&self.incoming)? + 4
                    } else {
                        false
                    };
                    ensure!(
                        complete,
                        NetworkFailure,
                        "peer closed an incomplete connection"
                    );
                    break;
                }
                Ok(n) => {
                    ensure!(
                        self.incoming.len() + n <= self.frame_max + HELLO_LEN + 64,
                        ResourceLimitExceeded,
                        "peer frame exceeds limit"
                    );
                    self.incoming.extend_from_slice(&chunk[..n]);
                    if extend {
                        self.deadline = Instant::now() + IDLE_TIMEOUT;
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(e) => return Err(network(e)),
            }
        }
        Ok(())
    }
    /// Derives both direction keys when the remote hello is complete.
    fn handshake(&mut self, secret: &Key, initiator: bool) -> Result<()> {
        if self.tx_key.is_some() || self.incoming.len() < HELLO_LEN {
            return Ok(());
        }
        ensure!(
            &self.incoming[..8] == MAGIC,
            AuthenticationFailed,
            "peer protocol differs"
        );
        let remote: [u8; HELLO_LEN] = self.incoming[..HELLO_LEN]
            .try_into()
            .map_err(|_| Error::new(ChurStatus::InvalidInput, "peer hello length"))?;
        self.incoming.drain(..HELLO_LEN);
        let (first, second) = if initiator {
            (&self.hello, &remote)
        } else {
            (&remote, &self.hello)
        };
        self.tx_key = Some(chur_crypto::peer::session_key(
            secret,
            first,
            second,
            u8::from(initiator),
        )?);
        self.rx_key = Some(chur_crypto::peer::session_key(
            secret,
            first,
            second,
            u8::from(!initiator),
        )?);
        Ok(())
    }
    fn seal(&mut self, bytes: &[u8]) -> Result<()> {
        ensure!(
            bytes.len() <= FRAME_MAX && self.outgoing.is_empty(),
            ResourceLimitExceeded,
            "peer output is full"
        );
        let key = self
            .tx_key
            .as_ref()
            .ok_or_else(|| Error::new(ChurStatus::Conflict, "peer handshake pending"))?;
        let sequence = self.tx_sequence.to_be_bytes();
        let sealed = aead::seal(
            key,
            &Nonce::chunk(&[0; 16], self.tx_sequence),
            bytes,
            &sequence,
        )?;
        self.tx_sequence = self.tx_sequence.checked_add(1).ok_or_else(|| {
            Error::new(ChurStatus::ResourceLimitExceeded, "peer sequence exhausted")
        })?;
        self.outgoing
            .extend_from_slice(&(sealed.len() as u32).to_be_bytes());
        self.outgoing.extend_from_slice(&sealed);
        Ok(())
    }
    /// Opens the next complete frame. Unauthenticated plaintext is never returned.
    fn open(&mut self) -> Result<Option<Zeroizing<Vec<u8>>>> {
        if self.tx_key.is_none() || self.incoming.len() < 4 {
            return Ok(None);
        }
        let size = frame_length(&self.incoming)?;
        ensure!(
            (16..=self.frame_max + 16).contains(&size),
            ResourceLimitExceeded,
            "peer frame length is invalid"
        );
        if self.incoming.len() < size + 4 {
            return Ok(None);
        }
        let key = self
            .rx_key
            .as_ref()
            .ok_or_else(|| Error::new(ChurStatus::AuthenticationFailed, "peer key absent"))?;
        let opened = aead::open(
            key,
            &Nonce::chunk(&[0; 16], self.rx_sequence),
            &self.incoming[4..4 + size],
            &self.rx_sequence.to_be_bytes(),
        )
        .map_err(|_| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "peer frame authentication failed",
            )
        })?;
        self.rx_sequence = self.rx_sequence.checked_add(1).ok_or_else(|| {
            Error::new(ChurStatus::ResourceLimitExceeded, "peer sequence exhausted")
        })?;
        self.incoming.drain(..size + 4);
        Ok(Some(opened))
    }
}
impl Drop for Channel {
    fn drop(&mut self) {
        let _ = self.stream.shutdown(Shutdown::Both);
    }
}
fn frame_length(incoming: &[u8]) -> Result<usize> {
    let header: [u8; 4] = incoming
        .get(..4)
        .and_then(|bytes| bytes.try_into().ok())
        .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "peer frame header"))?;
    Ok(u32::from_be_bytes(header) as usize)
}
fn network(_: std::io::Error) -> Error {
    Error::new(ChurStatus::NetworkFailure, "direct peer connection failed")
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;
    const LOOPBACK: IpAddr = IpAddr::V4(Ipv4Addr::LOCALHOST);
    fn ready(peer: &mut Peer) -> bool {
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            assert!(Instant::now() < deadline, "direct handshake did not finish");
            if peer.poll().unwrap() {
                return true;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    /// The connector sends the first frame; only then does the listener report a peer.
    fn join(listener: &mut Peer, secret: Key) -> Peer {
        let port = listener
            .listener
            .as_ref()
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let mut connector = Peer::connect(LOOPBACK, port, secret).unwrap();
        let deadline = Instant::now() + Duration::from_secs(3);
        while !connector.poll().unwrap() {
            assert!(Instant::now() < deadline, "direct handshake did not finish");
            listener.poll().unwrap();
            std::thread::sleep(Duration::from_millis(1));
        }
        connector.send(b"first").unwrap();
        assert_eq!(&*receive_sent(&mut connector, listener).unwrap(), b"first");
        assert!(ready(listener));
        connector
    }
    fn connected() -> (Peer, Peer) {
        let (mut listener, _, secret) = Peer::listen(LOOPBACK).unwrap();
        let connector = join(&mut listener, secret);
        (listener, connector)
    }
    fn receive_sent(source: &mut Peer, target: &mut Peer) -> Result<Zeroizing<Vec<u8>>> {
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            assert!(Instant::now() < deadline, "direct frame did not finish");
            source.flush()?;
            if let Some(received) = target.receive()? {
                return Ok(received);
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    fn transfer(source: &mut Peer, target: &mut Peer, bytes: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
        source.send(bytes)?;
        receive_sent(source, target)
    }
    /// Polls the listener until it closed the given number of failed connections.
    fn await_failures(listener: &mut Peer, failures: u32) {
        let deadline = Instant::now() + Duration::from_secs(3);
        while listener.failures < failures {
            assert!(
                Instant::now() < deadline,
                "failed connection was not closed"
            );
            assert!(!listener.poll().unwrap());
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    #[test]
    fn real_tcp_exchanges_large_bounded_frames_in_both_directions() {
        let (mut left, mut right) = connected();
        let original: Vec<u8> = (0..2_000_000).map(|i| (i % 251) as u8).collect();
        assert_eq!(
            &*transfer(&mut left, &mut right, &original).unwrap(),
            &original
        );
        assert_eq!(
            &*transfer(&mut right, &mut left, b"acknowledged").unwrap(),
            b"acknowledged"
        );
        assert_eq!(
            &*transfer(&mut left, &mut right, b"next sequence").unwrap(),
            b"next sequence"
        );
        assert!(left.send(&vec![0; FRAME_MAX + 1]).is_err());
    }
    #[test]
    fn the_listener_binds_only_the_given_interface_address() {
        let (listener, _, _) = Peer::listen(LOOPBACK).unwrap();
        let bound = listener.listener.as_ref().unwrap().local_addr().unwrap();
        assert_eq!(bound.ip(), LOOPBACK);
        let unspecified = Peer::listen(IpAddr::V4(Ipv4Addr::UNSPECIFIED));
        assert_eq!(
            unspecified.err().unwrap().status(),
            ChurStatus::InvalidInput
        );
    }
    #[test]
    fn a_wrong_invitation_is_closed_and_the_invitation_stays_open() {
        let (mut listener, port, secret) = Peer::listen(LOOPBACK).unwrap();
        let mut wrong = Peer::connect(LOOPBACK, port, Key::new([0x55; 32])).unwrap();
        while !wrong.poll().unwrap() {
            assert!(!listener.poll().unwrap());
            std::thread::sleep(Duration::from_millis(1));
        }
        wrong.send(b"private").unwrap();
        while !wrong.flush().unwrap() {
            std::thread::sleep(Duration::from_millis(1));
        }
        await_failures(&mut listener, 1);
        assert!(listener.channel.is_none() && listener.listener.is_some());
        let mut right = join(&mut listener, secret);
        assert_eq!(
            &*transfer(&mut listener, &mut right, b"after").unwrap(),
            b"after"
        );
        // A proven connection closes the invitation for every later host.
        assert!(listener.listener.is_none());
    }
    #[test]
    fn idle_and_malformed_connections_do_not_take_the_invitation() {
        let (mut listener, port, secret) = Peer::listen(LOOPBACK).unwrap();
        let _idle = TcpStream::connect((LOOPBACK, port)).unwrap();
        let mut garbage = TcpStream::connect((LOOPBACK, port)).unwrap();
        garbage.write_all(&[0; HELLO_LEN]).unwrap();
        await_failures(&mut listener, 1);
        let mut right = join(&mut listener, secret);
        assert_eq!(
            &*transfer(&mut right, &mut listener, b"proven").unwrap(),
            b"proven"
        );
    }
    #[test]
    fn failed_connections_and_the_invitation_deadline_are_bounded() {
        let (mut listener, port, _) = Peer::listen(LOOPBACK).unwrap();
        listener.failures = ATTEMPTS_MAX - 1;
        let mut garbage = TcpStream::connect((LOOPBACK, port)).unwrap();
        garbage.write_all(&[0; HELLO_LEN]).unwrap();
        let deadline = Instant::now() + Duration::from_secs(3);
        let error = loop {
            assert!(Instant::now() < deadline, "attempt bound was not enforced");
            match listener.poll() {
                Err(error) => break error,
                Ok(ready) => assert!(!ready),
            }
            std::thread::sleep(Duration::from_millis(1));
        };
        assert_eq!(error.status(), ChurStatus::AuthenticationFailed);
        let (mut expired, _, _) = Peer::listen(LOOPBACK).unwrap();
        expired.deadline = Instant::now();
        assert_eq!(
            expired.poll().unwrap_err().status(),
            ChurStatus::NetworkFailure
        );
    }
    #[test]
    fn a_truncated_frame_followed_by_disconnect_fails_without_waiting_for_idle_timeout() {
        let (mut left, mut right) = connected();
        let channel = left.channel.as_mut().unwrap();
        channel.outgoing.extend_from_slice(&100u32.to_be_bytes());
        channel.outgoing.extend_from_slice(&[0; 8]);
        while !left.flush().unwrap() {
            std::thread::sleep(Duration::from_millis(1));
        }
        drop(left);
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            assert!(
                Instant::now() < deadline,
                "a disconnected frame must not wait for idle timeout"
            );
            match right.receive() {
                Err(error) => {
                    assert_eq!(error.status(), ChurStatus::NetworkFailure);
                    break;
                }
                Ok(result) => assert!(result.is_none()),
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    #[test]
    fn replayed_encrypted_frames_and_tampered_bytes_fail_authentication() {
        let (mut left, mut right) = connected();
        left.send(b"one").unwrap();
        let replay = left.channel.as_ref().unwrap().outgoing.to_vec();
        assert_eq!(&*receive_sent(&mut left, &mut right).unwrap(), b"one");
        left.channel
            .as_mut()
            .unwrap()
            .outgoing
            .extend_from_slice(&replay);
        assert_eq!(
            receive_sent(&mut left, &mut right).unwrap_err().status(),
            ChurStatus::AuthenticationFailed
        );
        let (mut left, mut right) = connected();
        left.send(b"two").unwrap();
        *left.channel.as_mut().unwrap().outgoing.last_mut().unwrap() ^= 1;
        assert_eq!(
            receive_sent(&mut left, &mut right).unwrap_err().status(),
            ChurStatus::AuthenticationFailed
        );
    }
}
