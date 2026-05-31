import Foundation
import SwiftUI
import SharedUI
import UniformTypeIdentifiers
import UIKit

final class SwiftPlatformBridge: NSObject, @preconcurrency IosPlatformBridge, UIDocumentPickerDelegate {
    weak var presenter: UIViewController?

    private enum DocumentAction {
        case importConfig(IosTextCallback)
        case exportLogs(IosMessageCallback, URL)
    }

    private var documentAction: DocumentAction?

    func readClipboard() -> String? {
        UIPasteboard.general.string
    }

    func writeClipboard(text: String) {
        DispatchQueue.main.async {
            UIPasteboard.general.string = text
        }
    }

    func pickConfigText(callback: IosTextCallback) {
        DispatchQueue.main.async {
            self.documentAction = .importConfig(callback)
            let picker = UIDocumentPickerViewController(
                forOpeningContentTypes: [.plainText, .json, .data],
                asCopy: true
            )
            picker.delegate = self
            self.present(picker)
        }
    }

    func shareText(title: String, text: String) {
        DispatchQueue.main.async {
            let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            controller.title = title
            self.present(controller)
        }
    }

    func saveLogs(defaultName: String, content: String, callback: IosMessageCallback) {
        DispatchQueue.main.async {
            do {
                let url = try self.writeTemporaryFile(defaultName: defaultName, content: content)
                self.documentAction = .exportLogs(callback, url)
                let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
                picker.delegate = self
                self.present(picker)
            } catch {
                callback.onError(message: error.localizedDescription)
            }
        }
    }

    func shareLogs(defaultName: String, content: String, callback: IosMessageCallback) {
        DispatchQueue.main.async {
            do {
                let url = try self.writeTemporaryFile(defaultName: defaultName, content: content)
                let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
                controller.completionWithItemsHandler = { _, completed, _, error in
                    if let error {
                        callback.onError(message: error.localizedDescription)
                    } else if completed {
                        callback.onSuccess(message: "Logs shared")
                    } else {
                        callback.onError(message: "Log sharing cancelled")
                    }
                }
                self.present(controller)
            } catch {
                callback.onError(message: error.localizedDescription)
            }
        }
    }

    func showMessage(message: String) {
        DispatchQueue.main.async {
            guard let presenter = self.topPresenter() else { return }
            let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            presenter.present(alert, animated: true)
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let action = documentAction else { return }
        documentAction = nil

        switch action {
        case .importConfig(let callback):
            guard let url = urls.first else {
                callback.onError(message: "No file selected")
                return
            }
            let didAccess = url.startAccessingSecurityScopedResource()
            defer {
                if didAccess {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            do {
                callback.onSuccess(text: try String(contentsOf: url, encoding: .utf8))
            } catch {
                callback.onError(message: error.localizedDescription)
            }

        case .exportLogs(let callback, let tempUrl):
            callback.onSuccess(message: "Logs saved")
            try? FileManager.default.removeItem(at: tempUrl)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        guard let action = documentAction else { return }
        documentAction = nil

        switch action {
        case .importConfig(let callback):
            callback.onError(message: "File import cancelled")
        case .exportLogs(let callback, let tempUrl):
            callback.onError(message: "Log export cancelled")
            try? FileManager.default.removeItem(at: tempUrl)
        }
    }

    private func present(_ controller: UIViewController) {
        guard let presenter = topPresenter() else { return }
        if let popover = controller.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(
                x: presenter.view.bounds.midX,
                y: presenter.view.bounds.midY,
                width: 1,
                height: 1
            )
            popover.permittedArrowDirections = []
        }
        presenter.present(controller, animated: true)
    }

    private func topPresenter() -> UIViewController? {
        var top = presenter ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }?
            .rootViewController

        while let presented = top?.presentedViewController {
            top = presented
        }

        return top
    }

    private func writeTemporaryFile(defaultName: String, content: String) throws -> URL {
        let sanitized = (defaultName.isEmpty ? "olcbox-logs.txt" : defaultName)
            .replacingOccurrences(of: "/", with: "_")
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString)-\(sanitized)")
        try content.write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}
