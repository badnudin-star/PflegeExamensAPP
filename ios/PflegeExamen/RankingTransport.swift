import Foundation

// Fixed HTTPS origin, one explicitly requested operation, no cookies, redirects,
// cache or application-level retries. This transport is not wired to UI yet.
struct HTTPSRankingTransport: RankingAccountTransport {
    static func request(_ operation: RankingAccountOperation, token: String) throws -> URLRequest {
        guard validRankingHash(token) else { throw SecureRankingError.invalid }
        var path = "", method = "GET", body: Data?
        struct Registration: Encodable { let username: String }
        struct Closure: Encodable { let date: String }
        switch operation {
        case .status: path = "/participants/me"
        case .register(let name):
            guard validRankingName(name) else { throw SecureRankingError.invalid }
            path = "/participants"; method = "POST"; body = try JSONEncoder().encode(Registration(username: name))
        case .delete: path = "/participants/me"; method = "DELETE"
        case .submit(let payload):
            guard payload.count > 0 && payload.count <= 64 * 1024 else { throw SecureRankingError.invalid }
            path = "/quiz/attempt"; method = "POST"; body = payload
        case .lookup(let date):
            guard validArchiveDate(date) else { throw SecureRankingError.invalid }
            path = "/quiz/attempt/result?date=\(date)"
        case .close(let date):
            guard validArchiveDate(date) else { throw SecureRankingError.invalid }
            path = "/quiz/attempt/close"; method = "POST"; body = try JSONEncoder().encode(Closure(date: date))
        }
        guard let url = URL(string: "https://pflege-examen-ranking.badnudin.workers.dev" + path) else { throw SecureRankingError.invalid }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.httpMethod = method; request.httpBody = body; request.httpShouldHandleCookies = false
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        if body != nil { request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type") }
        return request
    }
    func send(_ operation: RankingAccountOperation, token: String) async throws -> RankingAccountReply {
        let request = try Self.request(operation, token: token)
        return try await withCheckedThrowingContinuation { continuation in
            RankingRequestRunner(request: request, continuation: continuation).start()
        }
    }
}
final class RankingRequestRunner: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {
    private let request: URLRequest
    private let testConfiguration: URLSessionConfiguration?
    private var continuation: CheckedContinuation<RankingAccountReply, Error>?
    private var data = Data()
    private var status: Int?
    private var receipt: String?
    private var rejected = false
    private var session: URLSession?
    init(request: URLRequest, continuation: CheckedContinuation<RankingAccountReply, Error>, testConfiguration: URLSessionConfiguration? = nil) {
        self.request = request; self.continuation = continuation; self.testConfiguration = testConfiguration
    }
    func start() {
        let config = testConfiguration ?? URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.httpShouldSetCookies = false
        config.urlCredentialStorage = nil; config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 60
        let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: config, delegate: self, delegateQueue: queue)
        self.session = session
        session.dataTask(with: request).resume()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        rejected = true; completionHandler(nil)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, response.expectedContentLength <= 4096 else {
            rejected = true; completionHandler(.cancel); return
        }
        status = http.statusCode
        receipt = http.value(forHTTPHeaderField: "X-Quiz-Deletion-Receipt")
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard data.count <= 4096 - self.data.count else { rejected = true; dataTask.cancel(); return }
        self.data.append(data)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let current = continuation; continuation = nil
        defer { session.finishTasksAndInvalidate(); self.session = nil }
        if !rejected, error == nil, let status = status {
            current?.resume(returning: RankingAccountReply(status: status, body: data, deletionReceipt: receipt))
        } else { current?.resume(throwing: error ?? SecureRankingError.unavailable) }
    }
}
