import Foundation
import Combine
import CryptoKit

// Local-only, manual marks; independent of catalog revisions. No accounts or exports.
final class ProgressStore: ObservableObject {
    private let defaults: UserDefaults
    private static let learnedKey = "ios.preview.learnedCards.v1"
    private static let favoriteKey = "ios.preview.favoriteCards.v1"
    private static let notesKey = "ios.preview.readNotes.v1"
    private static let casesKey = "ios.preview.completedCases.v1"
    private static let quizKey = "ios.preview.quizAnswers.v1"
    @Published private(set) var learned: Set<String>
    @Published private(set) var favorites: Set<String>
    @Published private(set) var readNotes: Set<String>
    @Published private(set) var completedCases: Set<String>
    @Published private(set) var quizAvailable: Bool
    @Published private(set) var quizRecords: [String: QuizRecord]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        learned = Set(defaults.stringArray(forKey: Self.learnedKey) ?? [])
        favorites = Set(defaults.stringArray(forKey: Self.favoriteKey) ?? [])
        readNotes = Set(defaults.stringArray(forKey: Self.notesKey) ?? [])
        completedCases = Set(defaults.stringArray(forKey: Self.casesKey) ?? [])
        if let raw = defaults.object(forKey: Self.quizKey) {
            if let data = raw as? Data, let records = try? JSONDecoder().decode([String: QuizRecord].self, from: data) {
                quizRecords = records; quizAvailable = true
            } else { quizRecords = [:]; quizAvailable = false }
        } else { quizRecords = [:]; quizAvailable = true }
    }
    func toggleCase(_ id: String) {
        if completedCases.contains(id) { completedCases.remove(id) } else { completedCases.insert(id) }
        defaults.set(completedCases.sorted(), forKey: Self.casesKey)
    }
    func answer(for quiz: Quiz) -> Int? {
        guard let record = quizRecords[quiz.id], record.fingerprint == quizFingerprint(quiz),
              (0..<quiz.options.count).contains(record.answer) else { return nil }
        return record.answer
    }
    func select(_ answer: Int, for quiz: Quiz) {
        guard quizAvailable, (0..<quiz.options.count).contains(answer) else { return }
        var updated = quizRecords
        updated[quiz.id] = QuizRecord(fingerprint: quizFingerprint(quiz), answer: answer)
        guard let data = try? JSONEncoder().encode(updated) else { return }
        defaults.set(data, forKey: Self.quizKey)
        quizRecords = updated
    }
    func clearAnswer(for quiz: Quiz) {
        guard quizAvailable else { return }
        var updated = quizRecords; updated.removeValue(forKey: quiz.id)
        guard let data = try? JSONEncoder().encode(updated) else { return }
        defaults.set(data, forKey: Self.quizKey)
        quizRecords = updated
    }
    // Reset only the supplied day's matching questions, without affecting other days.
    func resetQuiz(_ questions: [Quiz]) {
        guard quizAvailable else { return }
        var updated = quizRecords
        for quiz in questions where updated[quiz.id]?.fingerprint == quizFingerprint(quiz) {
            updated.removeValue(forKey: quiz.id)
        }
        guard let data = try? JSONEncoder().encode(updated) else { return }
        defaults.set(data, forKey: Self.quizKey)
        quizRecords = updated
    }
    func quizSummary(_ questions: [Quiz]) -> QuizSummary? {
        guard quizAvailable, !questions.isEmpty else { return nil }
        let answers = questions.map { answer(for: $0) }
        guard answers.allSatisfy({ $0 != nil }) else { return nil }
        var topics: [QuizTopicSummary] = []
        for (index, question) in questions.enumerated() {
            let correct = answers[index] == question.correctIndex
            if let existing = topics.firstIndex(where: { $0.topic == question.topic }) {
                topics[existing].total += 1
                if correct { topics[existing].correct += 1 }
            } else { topics.append(QuizTopicSummary(topic: question.topic, correct: correct ? 1 : 0, total: 1)) }
        }
        return QuizSummary(correct: topics.reduce(0) { $0 + $1.correct }, total: questions.count, topics: topics)
    }
    func toggleLearned(_ id: String) {
        if learned.contains(id) { learned.remove(id) } else { learned.insert(id) }
        defaults.set(learned.sorted(), forKey: Self.learnedKey)
    }
    func toggleFavorite(_ id: String) {
        if favorites.contains(id) { favorites.remove(id) } else { favorites.insert(id) }
        defaults.set(favorites.sorted(), forKey: Self.favoriteKey)
    }
    func toggleRead(_ topic: String) {
        if readNotes.contains(topic) { readNotes.remove(topic) } else { readNotes.insert(topic) }
        defaults.set(readNotes.sorted(), forKey: Self.notesKey)
    }
}

struct QuizRecord: Codable {
    let fingerprint: String
    let answer: Int
}
func quizFingerprint(_ quiz: Quiz) -> String {
    let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
    guard let data = try? encoder.encode(quiz) else { return "" }
    return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

struct QuizTopicSummary: Identifiable {
    let topic: String
    var correct: Int
    var total: Int
    var id: String { topic }
}
struct QuizSummary {
    let correct: Int
    let total: Int
    let topics: [QuizTopicSummary]
}
