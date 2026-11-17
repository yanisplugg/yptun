import Darwin
import NetworkExtension
import SharedUI
import SwiftUI
import UIKit
import WidgetKit

private func uncaughtExceptionHandler(_ exception: NSException) {
    let symbols = exception.callStackSymbols.joined(separator: "\n")
    let log = """
    [CRASH: NSException] \(exception.name.rawValue): \(exception.reason ?? "no reason")
    User Info: \(String(describing: exception.userInfo))
    Stack:
    \(symbols)
    """
    AppCrashReporter.saveCrashLog(log)
}

private func posixSignalHandler(_ sig: Int32) {
    let symbols = Thread.callStackSymbols.joined(separator: "\n")
    let log = """
    [CRASH: Signal \(sig)]
    Stack:
    \(symbols)
    """
    AppCrashReporter.saveCrashLog(log)
    exit(128 + sig)
}

/// Records uncaught exceptions and fatal signals to files so startup errors can be inspected.
enum AppCrashReporter {
    static let appGroup = "group.org.yptun.app"

    static func install() {
        NSSetUncaughtExceptionHandler(uncaughtExceptionHandler)
        for sig in [SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGTRAP] {
            signal(sig, posixSignalHandler)
        }
    }

    static func saveCrashLog(_ content: String) {
        NSLog("[YPtun Crash] %@", content)
        if let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let file = docs.appendingPathComponent("crash.log")
            try? content.write(to: file, atomically: true, encoding: .utf8)
        }
        if let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            let file = group.appendingPathComponent("crash.log")
            try? content.write(to: file, atomically: true, encoding: .utf8)
        }
    }
}

/// Home-screen quick actions («Подключить / Отключить VPN»).
final class AppDelegate: NSObject, UIApplicationDelegate {
    @MainActor static var onShortcut: ((UIApplicationShortcutItem) -> Void)? {
        didSet {
            if let item = pendingShortcut, let handler = onShortcut {
                pendingShortcut = nil
                handler(item)
            }
        }
    }
    @MainActor private static var pendingShortcut: UIApplicationShortcutItem?

    @MainActor static func deliver(_ item: UIApplicationShortcutItem) {
        if let handler = onShortcut {
            handler(item)
        } else {
            pendingShortcut = item
        }
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if let shortcutItem = options.shortcutItem {
            Self.deliver(shortcutItem)
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = QuickActionSceneDelegate.self
        return configuration
    }
}

/// Receives quick actions while the app is running.
final class QuickActionSceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        AppDelegate.deliver(shortcutItem)
        completionHandler(true)
    }
}

@main
struct OlcboxIosApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var platformBridge: SwiftPlatformBridge?
    @State private var coreBridge: SwiftCoreBridge?
    @State private var appSession: IosAppSession?
    @State private var startupError: String?
    @State private var hasBootstrapped = false

    init() {
        AppCrashReporter.install()
        LocalNotifications.shared.install()
        if #available(iOS 16.2, *) {
            NotificationCenter.default.addObserver(forName: NSNotification.Name("org.yptun.vpn.connected"), object: nil, queue: .main) { _ in
                LiveActivityManager.onConnected()
            }
            NotificationCenter.default.addObserver(forName: NSNotification.Name("org.yptun.vpn.disconnected"), object: nil, queue: .main) { _ in
                LiveActivityManager.onDisconnected()
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                Color.black.ignoresSafeArea()

                if let error = startupError {
                    StartupErrorView(error: error) {
                        startupError = nil
                        bootstrap()
                    }
                } else if let platformBridge, let appSession {
                    ComposeHostView(
                        platformBridge: platformBridge,
                        appSession: appSession
                    )
                    .ignoresSafeArea()
                    .onOpenURL { url in
                        handleUrl(url, session: appSession)
                    }
                } else {
                    ProgressView()
                        .tint(Color(red: 0.65, green: 0.50, blue: 0.95))
                }
            }
            .onAppear {
                if !hasBootstrapped {
                    hasBootstrapped = true
                    bootstrap()
                }
            }
        }
    }

    private func bootstrap() {
        do {
            let pb = SwiftPlatformBridge()
            let cb = SwiftCoreBridge()
            let session = IosAppFactory().createSession(
                platformBridge: pb,
                coreBridge: cb
            )
            self.platformBridge = pb
            self.coreBridge = cb
            self.appSession = session

            AppDelegate.onShortcut = { item in
                DispatchQueue.main.async {
                    Self.handleShortcut(item, session: session)
                }
            }
        } catch {
            let msg = "Ошибка инициализации: \(error.localizedDescription)\n\n\(Thread.callStackSymbols.joined(separator: "\n"))"
            AppCrashReporter.saveCrashLog(msg)
            self.startupError = msg
        }
    }

    private static func handleShortcut(_ item: UIApplicationShortcutItem, session: IosAppSession) {
        switch item.type {
        case "org.yptun.control.start":
            session.startVpn()
        case "org.yptun.control.stop":
            session.stopVpn()
        default:
            break
        }
    }

    private static let importSchemes: Set<String> = [
        "qwdtt", "openflux", "masterdns", "snolc", "csqtt", "freeturn", "olcrtc", "amneziawg",
    ]

    private func handleUrl(_ url: URL, session: IosAppSession) {
        if let scheme = url.scheme?.lowercased(), Self.importSchemes.contains(scheme) {
            session.importLink(text: url.absoluteString)
            return
        }
        guard url.scheme?.lowercased() == "yptun" else { return }
        let host = url.host?.lowercased() ?? ""

        if host == "control" {
            switch url.lastPathComponent.lowercased() {
            case "start": session.startVpn()
            case "stop": session.stopVpn()
            case "restart": session.restartVpn()
            case "auto": session.requestAutoSelect()
            default: break
            }
            return
        }

        let text = url.absoluteString
        if host == "import" {
            let prefix = "yptun://import/"
            guard text.count > prefix.count else { return }
            let raw = String(text.dropFirst(prefix.count))
            session.importLink(text: raw.removingPercentEncoding ?? raw)
            return
        }

        session.importLink(text: text)
    }
}

private struct ComposeHostView: UIViewControllerRepresentable {
    let platformBridge: SwiftPlatformBridge
    let appSession: IosAppSession

    func makeUIViewController(context: Context) -> UIViewController {
        do {
            let controller = appSession.createViewController()
            platformBridge.presenter = controller
            return controller
        } catch {
            let err = "Ошибка рендеринга интерфейса: \(error.localizedDescription)\n\(Thread.callStackSymbols.joined(separator: "\n"))"
            AppCrashReporter.saveCrashLog(err)
            let alert = UIAlertController(title: "Ошибка", message: err, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            let vc = UIViewController()
            vc.view.backgroundColor = .black
            DispatchQueue.main.async { vc.present(alert, animated: true) }
            return vc
        }
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        platformBridge.presenter = uiViewController
    }
}

private struct StartupErrorView: View {
    let error: String
    let onRetry: () -> Void
    @State private var copied = false

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 48))
                .foregroundStyle(.yellow)
                .padding(.top, 40)

            Text("Ошибка запуска YPtun")
                .font(.title2.weight(.bold))
                .foregroundStyle(.white)

            ScrollView {
                Text(error)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.85))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
            }
            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16)

            HStack(spacing: 16) {
                Button {
                    UIPasteboard.general.string = error
                    copied = true
                } label: {
                    Text(copied ? "Скопировано!" : "Скопировать ошибку")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color.purple, in: RoundedRectangle(cornerRadius: 12))
                }

                Button(action: onRetry) {
                    Text("Повторить")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color.white.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(Color.black.ignoresSafeArea())
    }
}
