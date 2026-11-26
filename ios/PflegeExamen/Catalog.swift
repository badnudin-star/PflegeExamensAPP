import Foundation

struct Catalog: Decodable {
    let schemaVersion: Int
    let revision: Int
    let notes: [Note]
    let cards: [Card]
    let cases: [StudyCase]
    let quizzes: [Quiz]

    func validate() throws {
        func text(_ value: String) -> Bool { !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        func sources(_ value: [String]) -> Bool { !value.isEmpty && value.allSatisfy(text) }
        func unique(_ values: [String]) -> Bool { values.allSatisfy(text) && Set(values).count == values.count }
        let topics = Set(notes.map(\.topic))
        guard schemaVersion == 1, revision >= 0, revision < 9_007_199_254_740_991,
              !notes.isEmpty, unique(notes.map(\.topic)), unique(cards.map(\.id)),
              unique(cases.map(\.id)), unique(quizzes.map(\.id)),
              notes.allSatisfy({ text($0.short) && text($0.balanced) && text($0.detailed) && sources($0.sources) }),
              cards.allSatisfy({ topics.contains($0.topic) && text($0.front) && text($0.back) && sources($0.sources) }),
              cases.allSatisfy({ text($0.published) && text($0.text) && sources($0.sources) &&
                  !$0.diseases.isEmpty && $0.diseases.allSatisfy(text) && !$0.questions.isEmpty &&
                  $0.questions.allSatisfy({ text($0.question) && text($0.answer) && sources($0.sources) }) }),
              quizzes.allSatisfy({ topics.contains($0.topic) && text($0.published) && text($0.question) &&
                  text($0.explanation) && sources($0.sources) && $0.options.count == 4 &&
                  $0.options.allSatisfy(text) && Set($0.options).count == 4 && (0..<4).contains($0.correctIndex) })
        else { throw CatalogError.invalid }
    }
    static func decode(_ data: Data) throws -> Catalog {
        guard data.count <= 8 * 1024 * 1024 else { throw CatalogError.invalid }
        let catalog = try JSONDecoder().decode(Catalog.self, from: data)
        try catalog.validate()
        return catalog
    }
    static func load(from url: URL) throws -> Catalog { try decode(Data(contentsOf: url)) }
}
enum CatalogError: Error { case invalid, older, conflict, incomplete }
struct Note: Decodable, Identifiable {
    var id: String { topic }
    let topic: String
    let short: String
    let balanced: String
    let detailed: String
    let sources: [String]
}
struct Card: Decodable, Identifiable {
    let id: String
    let topic: String
    let front: String
    let back: String
    let sources: [String]
}
struct StudyCase: Decodable, Identifiable {
    let id: String
    let published: String
    let title: String?
    let text: String
    let examDay: Int?
    let diseases: [String]
    let instructions: String?
    let answerNote: String?
    let sources: [String]
    let questions: [CaseQuestion]
}
struct CaseQuestion: Decodable {
    let question: String
    let answer: String
    let sources: [String]
}
struct Quiz: Decodable, Identifiable {
    let id: String
    let published: String
    let topic: String
    let question: String
    let options: [String]
    let correctIndex: Int
    let explanation: String
    let sources: [String]
}

// Only replace a validated catalog with a complete, non-rollback snapshot.
// A revision uniquely identifies its entire JSON payload, including unknown fields.
struct CatalogSnapshot {
    let data: Data
    let catalog: Catalog
    init(_ data: Data) throws { self.data = data; self.catalog = try Catalog.decode(data) }
    func replacing(with raw: Data) throws -> CatalogSnapshot {
        let next = try CatalogSnapshot(raw)
        guard Set(next.catalog.notes.map(\.topic)) == Set(catalog.notes.map(\.topic)) else { throw CatalogError.incomplete }
        guard next.catalog.revision >= catalog.revision else { throw CatalogError.older }
        if next.catalog.revision == catalog.revision {
            let oldObject = try JSONSerialization.jsonObject(with: data) as? NSDictionary
            let newObject = try JSONSerialization.jsonObject(with: raw) as? NSDictionary
            guard let oldObject = oldObject, let newObject = newObject, oldObject.isEqual(newObject) else { throw CatalogError.conflict }
        }
        return next
    }
}
