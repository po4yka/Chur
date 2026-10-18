// Procedural test media. No person, location, or third-party media is recorded.
import Foundation
import AVFoundation
import ImageIO
import CoreGraphics
import UniformTypeIdentifiers

let directory = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
let source = AVURLAsset(url: URL(fileURLWithPath: CommandLine.arguments[1]))
let identifier = "C58971C2-7681-41DD-8DA4-87D1F8D687A9"
let photoURL = directory.appendingPathComponent("still.jpg")
let videoURL = directory.appendingPathComponent("motion.mov")
let pixels = [UInt8](repeating: 120, count: 64 * 64 * 4)
let data = Data(pixels) as CFData
let provider = CGDataProvider(data: data)!
let image = CGImage(width: 64, height: 64, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: 256,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
let destination = CGImageDestinationCreateWithURL(photoURL as CFURL, UTType.jpeg.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(destination, image, [kCGImagePropertyMakerAppleDictionary: ["17": identifier]] as CFDictionary)
precondition(CGImageDestinationFinalize(destination))
try? FileManager.default.removeItem(at: videoURL)
let reader = try AVAssetReader(asset: source)
let writer = try AVAssetWriter(outputURL: videoURL, fileType: .mov)
let contentID = AVMutableMetadataItem()
contentID.identifier = .quickTimeMetadataContentIdentifier
contentID.value = identifier as NSString
writer.metadata = [contentID]
var streams: [(AVAssetReaderTrackOutput, AVAssetWriterInput)] = []
for type in [AVMediaType.video, .audio] {
    let track = source.tracks(withMediaType: type).first!
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
    output.alwaysCopiesSampleData = false; reader.add(output)
    let input = AVAssetWriterInput(mediaType: type, outputSettings: nil, sourceFormatHint: track.formatDescriptions.first as! CMFormatDescription)
    input.expectsMediaDataInRealTime = false; writer.add(input); streams.append((output, input))
}
var description: CMFormatDescription?
let spec: [CFString: Any] = [kCMMetadataFormatDescriptionMetadataSpecificationKey_Identifier: "mdta/com.apple.quicktime.still-image-time", kCMMetadataFormatDescriptionMetadataSpecificationKey_DataType: "com.apple.metadata.datatype.int8"]
precondition(CMMetadataFormatDescriptionCreateWithMetadataSpecifications(allocator: kCFAllocatorDefault, metadataType: kCMMetadataFormatType_Boxed, metadataSpecifications: [spec] as CFArray, formatDescriptionOut: &description) == noErr)
let metadataInput = AVAssetWriterInput(mediaType: .metadata, outputSettings: nil, sourceFormatHint: description)
let adaptor = AVAssetWriterInputMetadataAdaptor(assetWriterInput: metadataInput)
writer.add(metadataInput)
precondition(writer.startWriting()); precondition(reader.startReading()); writer.startSession(atSourceTime: .zero)
let item = AVMutableMetadataItem()
item.key = "com.apple.quicktime.still-image-time" as NSString; item.keySpace = .quickTimeMetadata
item.value = NSNumber(value: Int8(0)); item.dataType = "com.apple.metadata.datatype.int8"
precondition(adaptor.append(AVTimedMetadataGroup(items: [item], timeRange: CMTimeRange(start: CMTime(seconds: 1, preferredTimescale: 600), duration: CMTime(value: 1, timescale: 600)))))
metadataInput.markAsFinished()
let group = DispatchGroup()
for (index, stream) in streams.enumerated() {
    group.enter()
    stream.1.requestMediaDataWhenReady(on: DispatchQueue(label: "fixture.\(index)")) {
        while stream.1.isReadyForMoreMediaData {
            if let sample = stream.0.copyNextSampleBuffer() {
                precondition(stream.1.append(sample), "sample append")
            } else { stream.1.markAsFinished(); group.leave(); return }
        }
    }
}
group.wait()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }; done.wait()
precondition(writer.status == .completed, "movie write")
print("Generated matched photo/movie with audio and timed still marker")

// Build the portable immutable package from these exact fixture bytes.
import CryptoKit
func be<T: FixedWidthInteger>(_ value: T) -> Data { var n = value.bigEndian; return withUnsafeBytes(of: &n) { Data($0) } }
var table = Data(), payload = Data()
var manifest: [[String: Any]] = []
for (role, name, uti, mime) in [(UInt32(1), "still.jpg", "public.jpeg", "image/jpeg"), (UInt32(9), "motion.mov", "com.apple.quicktime-movie", "video/quicktime")] {
    let bytes = try Data(contentsOf: directory.appendingPathComponent(name))
    manifest.append(["type": role, "filename": name, "length": bytes.count, "sha256": SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()])
    let strings = [Data(name.utf8), Data(uti.utf8), Data(mime.utf8)]
    table += be(role); table += be(UInt64(bytes.count)); table += Data(SHA256.hash(data: bytes))
    for text in strings { table += be(UInt16(text.count)) }
    for text in strings { table += text }; payload += bytes
}
let header = Data("CHURLIVE".utf8) + be(UInt16(1)) + be(UInt16(2)) + be(UInt32(24 + table.count)) + be(UInt64(1_700_000_123_456)) + table
try (header + payload).write(to: directory.appendingPathComponent("native.churlive"))

try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys]).write(to: directory.appendingPathComponent("manifest.json"))
