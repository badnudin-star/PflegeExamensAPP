import Foundation

// Preview-only, offline snapshot. The CI validates the published catalog before
// embedding it; no secrets or user data are included in this file.
struct Catalog: Decodable {
    let schemaVersion: Int
    let revision: Int
    let notes: [Note]
    let cards: [Card]
    let cases: [StudyCase]
    let quizzes: [Quiz]

    func validate() throws {
        guard schemaVersion == 1, revision >= 0, !notes.isEmpty,
              Set(notes.map(\.topic)).count == notes.count,
              Set(cards.map(\.id)).count == cards.count,
              Set(cases.map(\.id)).count == cases.count,
              Set(quizzes.map(\.id)).count == quizzes.count,
              quizzes.allSatisfy({ $0.options.count == 4 && (0..<4).contains($0.correctIndex) })
        else { throw CatalogError.invalid }
    }
    static func load(from url: URL) throws -> Catalog {
        let data = try Data(contentsOf: url)
        guard data.count <= 8 * 1024 * 1024 else { throw CatalogError.invalid }
        let catalog = try JSONDecoder().decode(Catalog.self, from: data)
        try catalog.validate()
        return catalog
    }
}
enum CatalogError: Error { case invalid }
struct Note: Decodable, Identifiable {
    var id: String { topic }
    let topic: String
    let short: String
    let detailed: String
    let sources: [String]
}
struct Card: Decodable, Identifiable {
    let id: String
    let topic: String
    let front: String
    let back: String
}
struct StudyCase: Decodable, Identifiable {
    let id: String
    let title: String
    let text: String
    let examDay: Int?
    let questions: [CaseQuestion]
}
struct CaseQuestion: Decodable {
    let question: String
    let answer: String
}
struct Quiz: Decodable, Identifiable {
    let id: String
    let published: String
    let topic: String
    let question: String
    let options: [String]
    let correctIndex: Int
    let explanation: String
}
