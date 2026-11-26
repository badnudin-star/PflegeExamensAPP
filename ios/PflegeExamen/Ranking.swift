import Foundation
import Combine

struct RankingEntry: Decodable, Identifiable {
    let rank: Int
    let username: String
    let points: Int
    let quizzes: Int
    var id: Int { rank }
}
struct RankingSnapshot: Decodable {
    let generatedAtMillis: Int64
    let nextUpdateAtMillis: Int64
    let totalParticipants: Int
    let entries: [RankingEntry]
    static func decode(_ data: Data) throws -> RankingSnapshot {
        guard data.count <= 128 * 1024 else { throw CatalogError.invalid }
        let snapshot = try JSONDecoder().decode(RankingSnapshot.self, from: data)
        guard snapshot.generatedAtMillis > 0, snapshot.generatedAtMillis <= 253_402_300_799_999,
              snapshot.nextUpdateAtMillis > snapshot.generatedAtMillis,
              snapshot.nextUpdateAtMillis - snapshot.generatedAtMillis <= 3_600_000,
              snapshot.entries.count <= 100, snapshot.totalParticipants >= snapshot.entries.count else { throw CatalogError.invalid }
        for (index, entry) in snapshot.entries.enumerated() {
            guard entry.rank == index + 1, !entry.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  entry.username.utf16.count <= 24,
                  entry.username.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }),
                  entry.quizzes > 0, entry.quizzes <= Int.max / 50,
                  entry.points >= 0, entry.points <= entry.quizzes * 50,
                  index == 0 || snapshot.entries[index - 1].points >= entry.points else { throw CatalogError.invalid }
        }
        return snapshot
    }
}

// Read-only. No token, registration, score, answer or retry is ever sent.
final class RankingStore: ObservableObject {
    @Published private(set) var snapshot: RankingSnapshot?
    @Published private(set) var loading = false
    @Published private(set) var error = false
    private var fetcher: LimitedCatalogFetch?
    func load() {
        guard !loading else { return }
        loading = true; error = false
        let fetch = LimitedCatalogFetch(endpoint: .leaderboard) { [weak self] result in
            guard let self = self else { return }
            defer { self.loading = false; self.fetcher = nil }
            do { self.snapshot = try RankingSnapshot.decode(result.get()) }
            catch { self.error = true }
        }
        fetcher = fetch; fetch.start()
    }
}
