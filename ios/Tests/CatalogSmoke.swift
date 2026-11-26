import Foundation
import PDFKit

@main
struct CatalogSmoke {
    static func main() throws {
        let url = URL(fileURLWithPath: CommandLine.arguments[1])
        let raw = try Data(contentsOf: url)
        let catalog = try Catalog.decode(raw)
        precondition(!catalog.notes.isEmpty)
        print("Katalog Revision \(catalog.revision): \(catalog.notes.count) Themen, \(catalog.cards.count) Karten, \(catalog.cases.count) Fälle, \(catalog.quizzes.count) Quizfragen")
        let invalid = Catalog(schemaVersion: 99, revision: 0, notes: catalog.notes, cards: [], cases: [], quizzes: [])
        do { try invalid.validate(); fatalError("Ungültiges Schema akzeptiert") }
        catch CatalogError.invalid { print("Ungültiges Schema abgelehnt") }

        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: temporary) }
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
        let stored = temporary.appendingPathComponent("catalog.json")
        let store = CatalogStore(bundleURL: url, fileURL: stored)
        precondition(store.snapshot?.catalog.revision == catalog.revision)
        let unchanged = try store.applyDownloaded(raw)
        precondition(!unchanged)
        var object = try JSONSerialization.jsonObject(with: raw) as! [String: Any]
        object["revision"] = catalog.revision + 1
        let newer = try JSONSerialization.data(withJSONObject: object)
        let updated = try store.applyDownloaded(newer)
        precondition(updated)
        precondition(store.snapshot?.catalog.revision == catalog.revision + 1)
        let reopened = CatalogStore(bundleURL: url, fileURL: stored)
        precondition(reopened.snapshot?.catalog.revision == catalog.revision + 1)
        func rejected(_ data: Data) {
            do { try store.applyDownloaded(data); fatalError("Ungültiges Update akzeptiert") } catch {}
            precondition(store.snapshot?.catalog.revision == catalog.revision + 1)
            precondition((try? Data(contentsOf: stored)) == newer)
        }
        rejected(raw) // rollback
        object["revision"] = catalog.revision + 1
        object["unexpectedChange"] = true
        rejected(try JSONSerialization.data(withJSONObject: object)) // same revision conflict
        object.removeValue(forKey: "unexpectedChange")
        object["revision"] = catalog.revision + 2
        object["notes"] = []
        rejected(try JSONSerialization.data(withJSONObject: object)) // incomplete
        rejected(Data("{}".utf8))
        print("Katalogupdate: Persistenz, Rollback, Konflikt und Fehlerfall geprüft")

        let suite = "pflege.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let progress = ProgressStore(defaults: defaults)
        progress.toggleLearned("card-1"); progress.toggleFavorite("card-2"); progress.toggleRead("topic-1")
        let loaded = ProgressStore(defaults: defaults)
        precondition(loaded.learned == ["card-1"] && loaded.favorites == ["card-2"] && loaded.readNotes == ["topic-1"])
        loaded.toggleLearned("card-1"); loaded.toggleFavorite("card-2"); loaded.toggleRead("topic-1")
        let cleared = ProgressStore(defaults: defaults)
        precondition(cleared.learned.isEmpty && cleared.favorites.isEmpty && cleared.readNotes.isEmpty)
        print("Lernmarkierungen: Speichern, Wiederherstellen und Zurücksetzen geprüft")
        let pdfDirectory = URL(fileURLWithPath: CommandLine.arguments[2])
        let pdf = NotePDFLocator.locate(directory: pdfDirectory, source: "Lernzettel: Synthetisch.pdf", text: "Synthetischer Lernzettel.")
        precondition(pdf != nil)
        precondition(PDFDocument(url: pdf!)?.pageCount == 2)
        precondition(NotePDFLocator.locate(directory: pdfDirectory, source: "Lernzettel: Synthetisch.pdf", text: "Geänderter Text") == nil)
        precondition(NotePDFLocator.locate(directory: pdfDirectory, source: "Lernzettel: ../Synthetisch.pdf", text: "Synthetischer Lernzettel.") == nil)
        print("PDF-Zuordnung: übereinstimmenden Text, veralteten Text und Pfadschutz geprüft")
    }
}
