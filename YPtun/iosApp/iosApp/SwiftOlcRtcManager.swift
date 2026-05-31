import Darwin
import AVFoundation
import Foundation
import OlcRtcMobile
import SharedUI
import UIKit

final class SwiftOlcRtcManager: NSObject, @unchecked Sendable, IosOlcRtcBridge {
    private var logWriter: IosLogWriter?
    private var mobileLogWriter: MobileLogWriterAdapter?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private let lock = NSLock()

    func setLogWriter(writer: IosLogWriter?) {
        lock.lock()
        defer { lock.unlock() }

        logWriter = writer
        if let writer {
            let adapter = MobileLogWriterAdapter(writer: writer)
            mobileLogWriter = adapter
            MobileSetLogWriter(adapter)
        } else {
            mobileLogWriter = nil
            MobileSetLogWriter(nil)
        }
    }

    func start(request: IosOlcRtcStartRequest) -> IosBridgeResult {
        lock.lock()
        defer { lock.unlock() }

        MobileSetProviders()
        MobileSetTransport(request.transportName)
        MobileSetDNS("1.1.1.1:53")
        MobileSetVP8Options(Int(request.vp8Fps), Int(request.vp8BatchSize))

        if MobileIsRunning() {
            MobileStop()
        }

        var error: NSError?
        let started = MobileStartWithTransport(
            request.carrierName,
            request.transportName,
            request.roomId,
            request.clientId,
            request.keyHex,
            Int(request.socksPort),
            request.socksUser,
            request.socksPass,
            &error
        )
        guard started else {
            return IosBridgeResult(success: false, message: error?.localizedDescription ?? "olcRTC start failed")
        }

        let ready = MobileWaitReady(8_000, &error)
        guard ready else {
            MobileStop()
            endBackgroundTaskIfNeeded()
            deactivatePlaybackSession()
            return IosBridgeResult(success: false, message: error?.localizedDescription ?? "olcRTC start timed out")
        }

        activatePlaybackSession()
        beginBackgroundTaskIfNeeded()
        return IosBridgeResult(success: true, message: nil)
    }

    func stop() {
        lock.lock()
        defer { lock.unlock() }
        MobileStop()
        endBackgroundTaskIfNeeded()
        deactivatePlaybackSession()
    }

    func isRunning() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return MobileIsRunning()
    }

    func ping(request: IosOlcRtcCheckRequest) -> IosLongResult {
        let port = allocateLocalPort()
        guard port > 0 else {
            return IosLongResult(success: false, valueMillis: -1, message: "Could not allocate local SOCKS port")
        }

        var value: Int64 = -1
        var error: NSError?
        let success = MobilePing(
            request.carrierName,
            request.transportName,
            request.roomId,
            request.clientId,
            request.keyHex,
            port,
            Int(request.timeoutMillis),
            request.pingUrl,
            Int(request.vp8Fps),
            Int(request.vp8BatchSize),
            &value,
            &error
        )
        return IosLongResult(
            success: success,
            valueMillis: success ? value : -1,
            message: success ? nil : error?.localizedDescription
        )
    }

    func check(request: IosOlcRtcCheckRequest) -> IosLongResult {
        let port = allocateLocalPort()
        guard port > 0 else {
            return IosLongResult(success: false, valueMillis: -1, message: "Could not allocate local SOCKS port")
        }

        var value: Int64 = -1
        var error: NSError?
        let success = MobileCheck(
            request.carrierName,
            request.transportName,
            request.roomId,
            request.clientId,
            request.keyHex,
            port,
            Int(request.timeoutMillis),
            Int(request.vp8Fps),
            Int(request.vp8BatchSize),
            &value,
            &error
        )
        return IosLongResult(
            success: success,
            valueMillis: success ? value : -1,
            message: success ? nil : error?.localizedDescription
        )
    }

    private func allocateLocalPort() -> Int {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return -1 }
        defer { close(fd) }

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")

        let bindResult = withUnsafePointer(to: &addr) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else { return -1 }

        var boundAddr = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameResult = withUnsafeMutablePointer(to: &boundAddr) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        guard nameResult == 0 else { return -1 }

        return Int(UInt16(bigEndian: boundAddr.sin_port))
    }

    private func beginBackgroundTaskIfNeeded() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            self.lock.lock()
            let existingTask = self.backgroundTask
            self.lock.unlock()
            guard existingTask == .invalid else { return }

            var newTask: UIBackgroundTaskIdentifier = .invalid
            newTask = UIApplication.shared.beginBackgroundTask(withName: "Olcbox SOCKS") { [weak self] in
                self?.endBackgroundTaskIfNeeded()
            }

            self.lock.lock()
            if self.backgroundTask == .invalid {
                self.backgroundTask = newTask
            } else if newTask != .invalid {
                UIApplication.shared.endBackgroundTask(newTask)
            }
            let writer = self.logWriter
            self.lock.unlock()

            if newTask == .invalid {
                writer?.writeLog(message: "iOS background task unavailable; SOCKS pauses when the app is suspended")
            } else {
                writer?.writeLog(message: "iOS background task active; SOCKS can continue until the system suspends the app")
            }
        }
    }

    private func activatePlaybackSession() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
                try session.setActive(true)
            } catch {
                self.lock.lock()
                let writer = self.logWriter
                self.lock.unlock()
                writer?.writeLog(message: "iOS playback background mode unavailable: \(error.localizedDescription)")
            }
        }
    }

    private func deactivatePlaybackSession() {
        DispatchQueue.main.async { [weak self] in
            do {
                try AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
            } catch {
                self?.lock.lock()
                let writer = self?.logWriter
                self?.lock.unlock()
                writer?.writeLog(message: "iOS playback session cleanup failed: \(error.localizedDescription)")
            }
        }
    }

    private func endBackgroundTaskIfNeeded() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            self.lock.lock()
            let task = self.backgroundTask
            self.backgroundTask = .invalid
            let writer = self.logWriter
            self.lock.unlock()

            guard task != .invalid else { return }
            UIApplication.shared.endBackgroundTask(task)
            writer?.writeLog(message: "iOS background task ended")
        }
    }
}

private final class MobileLogWriterAdapter: NSObject, MobileLogWriterProtocol {
    private weak var writer: IosLogWriter?

    init(writer: IosLogWriter) {
        self.writer = writer
    }

    func writeLog(_ msg: String?) {
        writer?.writeLog(message: msg ?? "")
    }
}
