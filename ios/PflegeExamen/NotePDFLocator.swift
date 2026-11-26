import Foundation
import CryptoKit

// The manifest is produced by bundle_note_pdfs.py only after comparing PDF text
// against the exact bundled catalog. A stale PDF must never replace newer text.
enum NotePDFLocator {
    static func fileName(_ source: String) -> String? {
        let prefix = "Lernzettel: "
        guard source.hasPrefix(prefix) else { return nil }
        let name = String(source.dropFirst(prefix.count))
        guard name.range(of: #"^[A-Za-z0-9_]+\.pdf$"#, options: .regularExpression) != nil else { return nil }
        return name
    }
    static func locate(directory: URL, source: String, text: String) -> URL? {
        guard let name = fileName(source),
              let manifest = try? Data(contentsOf: directory.appendingPathComponent("manifest.json")),
              manifest.count <= 1024 * 1024,
              let hashes = try? JSONDecoder().decode([String: String].self, from: manifest) else { return nil }
        let digest = SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
        let url = directory.appendingPathComponent(name)
        guard hashes[name] == digest, FileManager.default.fileExists(atPath: url.path) else { return nil }
        return url
    }
}
