import Foundation

@main
struct CatalogSmoke {
    static func main() throws {
        let url = URL(fileURLWithPath: CommandLine.arguments[1])
        let catalog = try Catalog.load(from: url)
        precondition(!catalog.notes.isEmpty)
        print("Katalog Revision \(catalog.revision): \(catalog.notes.count) Themen, \(catalog.cards.count) Karten, \(catalog.cases.count) Fälle, \(catalog.quizzes.count) Quizfragen")
        let invalid = Catalog(schemaVersion: 99, revision: 0, notes: catalog.notes, cards: [], cases: [], quizzes: [])
        do { try invalid.validate(); fatalError("Ungültiges Schema akzeptiert") }
        catch CatalogError.invalid { print("Ungültiges Schema abgelehnt") }
    }
}
