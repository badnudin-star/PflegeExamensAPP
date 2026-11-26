import Foundation
import Combine

// The public endpoint sees the device IP; only invoked after a user taps refresh.
final class CatalogStore: ObservableObject {
    @Published private(set) var snapshot: CatalogSnapshot?
    @Published private(set) var loading = false
    @Published private(set) var message = ""
    private let fileURL: URL?
    private var fetcher: LimitedCatalogFetch?

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
    func refresh() {
        guard !loading, snapshot != nil else { return }
        loading = true
        message = ""
        let fetch = LimitedCatalogFetch { [weak self] result in
            guard let self = self else { return }
            defer { self.loading = false; self.fetcher = nil }
            do {
                let changed = try self.applyDownloaded(result.get())
                let revision = self.snapshot?.catalog.revision ?? 0
                self.message = changed ? "Inhalte aktualisiert: Revision \(revision)." : "Der Katalog ist aktuell (Revision \(revision))."
            } catch {
                self.message = "Aktualisierung nicht möglich. Der bisherige Offline-Katalog bleibt erhalten."
            }
        }
        fetcher = fetch
        fetch.start()
    }
}

// Stream into a bounded buffer, forbid redirects, cookies and cached responses.
final class LimitedCatalogFetch: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {
    private let done: (Result<Data, Error>) -> Void
    private var buffer = Data()
    private var failure = false
    private var session: URLSession?
    init(done: @escaping (Result<Data, Error>) -> Void) { self.done = done }
    func start() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpCookieStorage = nil
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60
        let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
        self.session = session
        var request = URLRequest(url: URL(string: "https://pflegeexamen-content.badnudin.workers.dev/content")!)
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
              response.expectedContentLength <= 8 * 1024 * 1024 else {
            failure = true; completionHandler(.cancel); return
        }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard buffer.count + data.count <= 8 * 1024 * 1024 else { failure = true; dataTask.cancel(); return }
        buffer.append(data)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let result: Result<Data, Error> = (failure || error != nil) ? .failure(error ?? CatalogError.invalid) : .success(buffer)
        session.finishTasksAndInvalidate()
        DispatchQueue.main.async { self.done(result) }
    }
}
