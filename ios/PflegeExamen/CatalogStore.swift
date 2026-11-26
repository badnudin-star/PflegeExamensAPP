import Foundation
import Combine

// The public endpoint sees the device IP. Refresh occurs in the foreground, never through Drive credentials.
final class CatalogStore: ObservableObject {
    @Published private(set) var snapshot: CatalogSnapshot?
    @Published private(set) var loading = false
    @Published private(set) var message = ""
    private let fileURL: URL?
    private var fetcher: LimitedCatalogFetch?
    private var schedule = AutoRefreshSchedule()

    init(bundleURL: URL?, fileURL: URL? = nil) {
        self.fileURL = fileURL ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?.appendingPathComponent("learning-catalog-v1.json")
        guard let url = bundleURL, let bundled = try? CatalogSnapshot(Data(contentsOf: url)) else { return }
        if let file = self.fileURL, let data = try? Data(contentsOf: file),
           let stored = try? bundled.replacing(with: data) { snapshot = stored }
        else { snapshot = bundled }
    }
    @discardableResult
    func applyDownloaded(_ data: Data) throws -> Bool {
        guard let current = snapshot, let file = fileURL else { throw CatalogError.invalid }
        let next = try current.replacing(with: data)
        guard next.catalog.revision > current.catalog.revision else { return false }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: file, options: .atomic)
        snapshot = next
        return true
    }
    func refreshIfDue(now: Date = Date()) {
        if schedule.shouldRefresh(now: now) { refresh() }
    }
    func refresh() {
        guard !loading, snapshot != nil else { return }
        schedule.started(now: Date())
        loading = true
        message = ""
        let fetch = LimitedCatalogFetch { [weak self] result in
            guard let self = self else { return }
            defer { self.loading = false; self.fetcher = nil }
            do {
                let changed = try self.applyDownloaded(result.get())
                let revision = self.snapshot?.catalog.revision ?? 0
                self.message = changed ? "Inhalte aktualisiert: Revision \(revision)." : "Der Katalog ist aktuell (Revision \(revision))."
                self.schedule.completed(now: Date(), success: true)
            } catch {
                self.schedule.completed(now: Date(), success: false)
                self.message = "Aktualisierung nicht möglich. Der bisherige Offline-Katalog bleibt erhalten."
            }
        }
        fetcher = fetch
        fetch.start()
    }
}

// Stream into a bounded buffer, forbid redirects, cookies and cached responses.
enum PublicEndpoint {
    case catalog, leaderboard, archivePage(String?), archiveDay(String)
    var url: URL? {
        switch self {
        case .catalog: return URL(string: "https://pflegeexamen-content.badnudin.workers.dev/content")
        case .leaderboard: return URL(string: "https://pflege-examen-ranking.badnudin.workers.dev/leaderboard")
        case .archivePage(let before):
            guard before == nil || validArchiveDate(before!) else { return nil }
            var parts = URLComponents(string: "https://pflegeexamen-content.badnudin.workers.dev/quiz-archive")!
            parts.queryItems = [URLQueryItem(name: "limit", value: "30")]
            if let before = before { parts.queryItems?.append(URLQueryItem(name: "before", value: before)) }
            return parts.url
        case .archiveDay(let date):
            guard validArchiveDate(date) else { return nil }
            return URL(string: "https://pflegeexamen-content.badnudin.workers.dev/quiz-archive/\(date)")
        }
    }
    var maxBytes: Int {
        switch self {
        case .catalog: return 8 * 1024 * 1024
        case .leaderboard: return 128 * 1024
        case .archivePage: return 16 * 1024
        case .archiveDay: return 1024 * 1024
        }
    }
}
final class LimitedCatalogFetch: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {
    private let endpoint: PublicEndpoint
    private let done: (Result<Data, Error>) -> Void
    private var buffer = Data()
    private var failure = false
    private var session: URLSession?
    init(endpoint: PublicEndpoint = .catalog, done: @escaping (Result<Data, Error>) -> Void) { self.endpoint = endpoint; self.done = done }
    func start() {
        guard let url = endpoint.url else { DispatchQueue.main.async { self.done(.failure(CatalogError.invalid)) }; return }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpCookieStorage = nil
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60
        let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
        self.session = session
        var request = URLRequest(url: url)
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        session.dataTask(with: request).resume()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        failure = true; completionHandler(nil)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              response.expectedContentLength <= Int64(endpoint.maxBytes) else {
            failure = true; completionHandler(.cancel); return
        }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard data.count <= endpoint.maxBytes - buffer.count else { failure = true; dataTask.cancel(); return }
        buffer.append(data)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let result: Result<Data, Error> = (failure || error != nil) ? .failure(error ?? CatalogError.invalid) : .success(buffer)
        session.finishTasksAndInvalidate()
        DispatchQueue.main.async { self.done(result) }
    }
}

// No guaranteed background task. One check per hour while foregrounded; failures retry after 30 minutes.
struct AutoRefreshSchedule {
    private(set) var next = Date.distantPast
    func shouldRefresh(now: Date) -> Bool { now >= next }
    mutating func started(now: Date) { next = now.addingTimeInterval(30 * 60) }
    mutating func completed(now: Date, success: Bool) { next = now.addingTimeInterval(success ? 3600 : 1800) }
}
