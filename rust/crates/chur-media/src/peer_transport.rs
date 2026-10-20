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

/// One foreground-only invitation or connected peer. Secrets never enter durable storage.
pub struct Peer {
    listener: Option<TcpListener>,
    stream: Option<TcpStream>,
    secret: Key,
    hello: [u8; HELLO_LEN],
    initiator: bool,
    tx_key: Option<Key>,
    rx_key: Option<Key>,
    tx_sequence: u64,
    rx_sequence: u64,
    incoming: Vec<u8>,
    outgoing: Zeroizing<Vec<u8>>,
    sent: usize,
    deadline: Instant,
}
impl Peer {
    /// Opens a one-use invitation on a random device port.
    pub fn listen() -> Result<(Self, u16, Key)> {
        let listener = TcpListener::bind((std::net::Ipv4Addr::UNSPECIFIED, 0)).map_err(network)?;
        listener.set_nonblocking(true).map_err(network)?;
        let port = listener.local_addr().map_err(network)?.port();
        let secret = Key::new(random::array()?);
        let mut peer = Self::new(secret.duplicate(), false)?;
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
        let mut peer = Self::new(secret, true)?;
        peer.attach(stream)?;
        Ok(peer)
    }
    fn new(secret: Key, initiator: bool) -> Result<Self> {
        let mut hello = [0; HELLO_LEN];
        hello[..8].copy_from_slice(MAGIC);
        hello[8..].copy_from_slice(&random::array::<32>()?);
        Ok(Self {
            listener: None,
            stream: None,
            secret,
            hello,
            initiator,
            tx_key: None,
            rx_key: None,
            tx_sequence: 0,
            rx_sequence: 0,
            incoming: Vec::new(),
            outgoing: Zeroizing::new(Vec::new()),
            sent: 0,
            deadline: Instant::now() + Duration::from_secs(120),
        })
    }
    fn attach(&mut self, stream: TcpStream) -> Result<()> {
        stream.set_nonblocking(true).map_err(network)?;
        stream.set_nodelay(true).map_err(network)?;
        self.stream = Some(stream);
        self.listener = None;
        self.outgoing.extend_from_slice(&self.hello);
        Ok(())
    }
    /// Pumps bounded nonblocking IO; true means the two fresh handshake challenges arrived.
    pub fn poll(&mut self) -> Result<bool> {
        ensure!(
            Instant::now() < self.deadline,
            NetworkFailure,
            "peer connection timed out"
        );
        if let Some(listener) = &self.listener {
            match listener.accept() {
                Ok((stream, _)) => self.attach(stream)?,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => return Ok(false),
                Err(e) => return Err(network(e)),
            }
        }
        let stream = self
            .stream
            .as_mut()
            .ok_or_else(|| Error::new(ChurStatus::NetworkFailure, "peer disconnected"))?;
        // A bounded pump never holds up a vault lock waiting for a peer.
        for _ in 0..16 {
            if self.sent == self.outgoing.len() {
                break;
            }
            match stream.write(&self.outgoing[self.sent..]) {
                Ok(0) => return Err(Error::new(ChurStatus::NetworkFailure, "peer write ended")),
                Ok(n) => {
                    self.sent += n;
                    self.deadline = Instant::now() + Duration::from_secs(120);
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
            match stream.read(&mut chunk) {
                Ok(0) => {
                    // A full final frame survives FIN; a truncated frame fails immediately.
                    let complete = if self.tx_key.is_none() {
                        self.incoming.len() >= HELLO_LEN
                    } else if self.incoming.len() >= 4 {
                        let length =
                            u32::from_be_bytes(self.incoming[..4].try_into().map_err(|_| {
                                Error::new(ChurStatus::InvalidInput, "peer frame header")
                            })?) as usize;
                        self.incoming.len() >= length + 4
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
                        self.incoming.len() + n <= FRAME_MAX + HELLO_LEN + 64,
                        ResourceLimitExceeded,
                        "peer frame exceeds limit"
                    );
                    self.incoming.extend_from_slice(&chunk[..n]);
                    self.deadline = Instant::now() + Duration::from_secs(120);
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(e) => return Err(network(e)),
            }
        }
        if self.tx_key.is_none() && self.incoming.len() >= HELLO_LEN {
            ensure!(
                &self.incoming[..8] == MAGIC,
                AuthenticationFailed,
                "peer protocol differs"
            );
            let remote: [u8; HELLO_LEN] = self.incoming[..HELLO_LEN]
                .try_into()
                .map_err(|_| Error::new(ChurStatus::InvalidInput, "peer hello length"))?;
            self.incoming.drain(..HELLO_LEN);
            let (first, second) = if self.initiator {
                (&self.hello, &remote)
            } else {
                (&remote, &self.hello)
            };
            self.tx_key = Some(chur_crypto::peer::session_key(
                &self.secret,
                first,
                second,
                u8::from(self.initiator),
            )?);
            self.rx_key = Some(chur_crypto::peer::session_key(
                &self.secret,
                first,
                second,
                u8::from(!self.initiator),
            )?);
        }
        Ok(self.tx_key.is_some() && self.outgoing.is_empty())
    }
    /// Queues exactly one authenticated frame. Call flush before another send.
    pub fn send(&mut self, bytes: &[u8]) -> Result<()> {
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
    /// Flushes queued output without blocking.
    pub fn flush(&mut self) -> Result<bool> {
        self.poll()?;
        Ok(self.outgoing.is_empty())
    }
    /// Receives a full verified frame, retaining incomplete TCP reads for the next poll.
    pub fn receive(&mut self) -> Result<Option<Zeroizing<Vec<u8>>>> {
        self.poll()?;
        if self.tx_key.is_none() || self.incoming.len() < 4 {
            return Ok(None);
        }
        let size = u32::from_be_bytes(
            self.incoming[..4]
                .try_into()
                .map_err(|_| Error::new(ChurStatus::InvalidInput, "peer frame header"))?,
        ) as usize;
        ensure!(
            (16..=FRAME_MAX + 16).contains(&size),
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
impl Drop for Peer {
    fn drop(&mut self) {
        if let Some(stream) = &self.stream {
            let _ = stream.shutdown(Shutdown::Both);
        }
    }
}
fn network(_: std::io::Error) -> Error {
    Error::new(ChurStatus::NetworkFailure, "direct peer connection failed")
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;
    fn connected(wrong_secret: bool) -> (Peer, Peer) {
        let (mut listener, port, secret) = Peer::listen().unwrap();
        let secret = if wrong_secret {
            Key::new([0x55; 32])
        } else {
            secret
        };
        let mut connector = Peer::connect("127.0.0.1".parse().unwrap(), port, secret).unwrap();
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            assert!(Instant::now() < deadline, "direct handshake did not finish");
            let left = listener.poll().unwrap();
            let right = connector.poll().unwrap();
            if left && right {
                break;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
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
    #[test]
    fn real_tcp_exchanges_large_bounded_frames_in_both_directions() {
        let (mut left, mut right) = connected(false);
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
    fn wrong_invitation_is_never_accepted_as_a_message() {
        let (mut left, mut right) = connected(true);
        assert_eq!(
            transfer(&mut left, &mut right, b"private")
                .unwrap_err()
                .status(),
            ChurStatus::AuthenticationFailed
        );
    }
    #[test]
    fn a_truncated_frame_followed_by_disconnect_fails_without_waiting_for_idle_timeout() {
        let (mut left, mut right) = connected(false);
        left.outgoing.extend_from_slice(&100u32.to_be_bytes());
        left.outgoing.extend_from_slice(&[0; 8]);
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
        let (mut left, mut right) = connected(false);
        left.send(b"one").unwrap();
        let replay = left.outgoing.to_vec();
        assert_eq!(&*receive_sent(&mut left, &mut right).unwrap(), b"one");
        left.outgoing.extend_from_slice(&replay);
        assert_eq!(
            receive_sent(&mut left, &mut right).unwrap_err().status(),
            ChurStatus::AuthenticationFailed
        );
        let (mut left, mut right) = connected(false);
        left.send(b"two").unwrap();
        *left.outgoing.last_mut().unwrap() ^= 1;
        assert_eq!(
            receive_sent(&mut left, &mut right).unwrap_err().status(),
            ChurStatus::AuthenticationFailed
        );
    }
}
