import Foundation
import Combine

func validArchiveDate(_ value: String) -> Bool {
    guard value.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#, options: .regularExpression) != nil else { return false }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = "yyyy-MM-dd"
    formatter.isLenient = false
    guard let date = formatter.date(from: value) else { return false }
    return formatter.string(from: date) == value
}
struct ArchiveSummary: Decodable, Identifiable {
    let date: String
    let questions: Int
    var id: String { date }
}
struct ArchivePage: Decodable {
    let days: [ArchiveSummary]
    let nextBefore: String?
    static func decode(_ data: Data, before: String?) throws -> ArchivePage {
        guard data.count <= 16 * 1024, before == nil || validArchiveDate(before!) else { throw CatalogError.invalid }
        let page = try JSONDecoder().decode(ArchivePage.self, from: data)
        guard page.days.count <= 30, page.days.allSatisfy({ validArchiveDate($0.date) && $0.questions > 0 && (before == nil || $0.date < before!) }),
              zip(page.days, page.days.dropFirst()).allSatisfy({ pair in pair.0.date > pair.1.date }),
              page.nextBefore == nil || (!page.days.isEmpty && page.nextBefore == page.days.last?.date) else { throw CatalogError.invalid }
        return page
    }
}
struct ArchiveDay: Decodable {
    let date: String
    let quizzes: [Quiz]
    static func decode(_ data: Data, expected: String, catalog: Catalog) throws -> ArchiveDay {
        guard data.count <= 1024 * 1024, validArchiveDate(expected) else { throw CatalogError.invalid }
        let day = try JSONDecoder().decode(ArchiveDay.self, from: data)
        guard day.date == expected, !day.quizzes.isEmpty, day.quizzes.allSatisfy({ $0.published == expected }) else { throw CatalogError.invalid }
        try Catalog(schemaVersion: 1, revision: 0, notes: catalog.notes, cards: [], cases: [], quizzes: day.quizzes).validate()
        return day
    }
}

// An existing or damaged day is never silently replaced. No private progress is stored here.
struct ArchiveCache {
    let directory: URL
    func path(_ date: String) throws -> URL {
        guard validArchiveDate(date) else { throw CatalogError.invalid }
        return directory.appendingPathComponent(date + ".json")
    }
    func dates() -> [String] {
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return [] }
        return names.filter { $0.hasSuffix(".json") }.map { String($0.dropLast(5)) }.filter(validArchiveDate).sorted(by: >)
    }
    func read(_ date: String, catalog: Catalog) throws -> ArchiveDay? {
        let url = try path(date)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        guard size <= 1024 * 1024 else { throw CatalogError.invalid }
        return try ArchiveDay.decode(Data(contentsOf: url), expected: date, catalog: catalog)
    }
    func writeNew(_ raw: Data, date: String, catalog: Catalog) throws -> ArchiveDay {
        let day = try ArchiveDay.decode(raw, expected: date, catalog: catalog)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = try path(date)
        try raw.write(to: url, options: .withoutOverwriting)
        let reread = try Data(contentsOf: url)
        guard reread == raw else { throw CatalogError.invalid }
        return day
    }
}

final class QuizArchiveStore: ObservableObject {
    let catalog: Catalog
    private let cache: ArchiveCache
    @Published private(set) var summaries: [ArchiveSummary] = []
    @Published private(set) var cachedDates: [String] = []
    @Published private(set) var nextBefore: String?
    @Published private(set) var hasLoaded = false
    @Published private(set) var loadingPage = false
    @Published private(set) var loadingDay = false
    @Published private(set) var day: ArchiveDay?
    @Published private(set) var message = ""
    private var pageFetcher: LimitedCatalogFetch?
    private var dayFetcher: LimitedCatalogFetch?
    private var generation = 0

    init(catalog: Catalog, directory: URL? = nil) {
        self.catalog = catalog
        let dir = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("quiz-archive-v1", isDirectory: true)
        cache = ArchiveCache(directory: dir)
        cachedDates = cache.dates()
    }
    func loadPage(more: Bool = false) {
        guard !loadingPage, !more || nextBefore != nil else { return }
        let before = more ? nextBefore : nil
        loadingPage = true; message = ""
        let fetch = LimitedCatalogFetch(endpoint: .archivePage(before)) { [weak self] result in
            guard let self = self else { return }
            defer { self.loadingPage = false; self.pageFetcher = nil }
            do {
                let page = try ArchivePage.decode(result.get(), before: before)
                self.summaries = more ? self.summaries + page.days : page.days
                self.nextBefore = page.nextBefore; self.hasLoaded = true
            } catch { self.message = "Archiv derzeit nicht erreichbar oder ungültig. Gespeicherte Tage bleiben verfügbar." }
        }
        pageFetcher = fetch; fetch.start()
    }
    func loadDay(_ date: String) {
        generation += 1
        let request = generation
        day = nil; message = ""; loadingDay = false
        do {
            if let cached = try cache.read(date, catalog: catalog) { day = cached; return }
        } catch { message = "Gespeicherter Archivtag ist nicht lesbar und wird nicht überschrieben."; return }
        loadingDay = true
        let fetch = LimitedCatalogFetch(endpoint: .archiveDay(date)) { [weak self] result in
            guard let self = self, self.generation == request else { return }
            defer { self.loadingDay = false; self.dayFetcher = nil }
            do {
                self.day = try self.cache.writeNew(result.get(), date: date, catalog: self.catalog)
                self.cachedDates = self.cache.dates()
            } catch { self.message = "Archivtag konnte nicht sicher geladen oder gespeichert werden." }
        }
        dayFetcher = fetch; fetch.start()
    }
}
