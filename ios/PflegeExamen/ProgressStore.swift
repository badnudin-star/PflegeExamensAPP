import Foundation
import Combine

// Local-only, manual marks; independent of catalog revisions. No accounts or exports.
final class ProgressStore: ObservableObject {
    private let defaults: UserDefaults
    private static let learnedKey = "ios.preview.learnedCards.v1"
    private static let favoriteKey = "ios.preview.favoriteCards.v1"
    private static let notesKey = "ios.preview.readNotes.v1"
    @Published private(set) var learned: Set<String>
    @Published private(set) var favorites: Set<String>
    @Published private(set) var readNotes: Set<String>

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        learned = Set(defaults.stringArray(forKey: Self.learnedKey) ?? [])
        favorites = Set(defaults.stringArray(forKey: Self.favoriteKey) ?? [])
        readNotes = Set(defaults.stringArray(forKey: Self.notesKey) ?? [])
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
