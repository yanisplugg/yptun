import Foundation
import Testing
@testable import YPtunAppUI
import YPtunShared
import YPtunVPNControl

@MainActor
private final class RecordingVPNController: AppleVPNControlling {
    var onStateChange: ((VPNConnectionState) -> Void)?
    var state: VPNConnectionState = .disconnected
    var startCount = 0
    var restartCount = 0
    var stopCount = 0
    var waitResults = [true]

    func currentState() async -> VPNConnectionState { state }

    func start() async throws {
        startCount += 1
        state = .connected
        onStateChange?(.connected)
    }

    func stop() async {
        stopCount += 1
        state = .disconnected
        onStateChange?(.disconnected)
    }

    func restart() async throws {
        restartCount += 1
        state = .connected
        onStateChange?(.connected)
    }

    func waitUntilConnected(timeout: Duration) async throws -> Bool {
        waitResults.isEmpty ? false : waitResults.removeFirst()
    }
}

@MainActor
private final class SelectionRecorder {
    var locationIDs: [String] = []
    var locationID: String? { locationIDs.last }
}

@MainActor
struct YPtunApplicationModelTests {
    @Test func sharedTunnelRequestRoundTripsForWidgetAndExtension() throws {
        let request = AppleTunnelRequest(
            locationJSON: "{\"name\":\"Primary\"}",
            trafficJSON: "{}",
            routingJSON: "{}",
            routingProfilesJSON: "{}",
            appBehaviorJSON: "{}",
            warpConfig: "[Interface]",
            deviceID: "device-id"
        )

        let decoded = try JSONDecoder().decode(
            AppleTunnelRequest.self,
            from: JSONEncoder().encode(request)
        )

        #expect(decoded == request)
    }

    @Test func primaryActionAddsALocationWhenTheCatalogIsEmpty() {
        let model = YPtunApplicationModel()

        model.requestPrimaryAction()

        #expect(model.isAddLocationPresented)
        #expect(model.presentedNotice == nil)
    }

    @Test func incompleteLocationExplainsHowToRecover() {
        let location = LocationListItem(
            id: "needs-user",
            name: "VK-TURN",
            detail: "Требуется привязка",
            isComplete: false,
            needsUserAction: true,
            isFromSubscription: false
        )
        let model = YPtunApplicationModel(
            locations: [location],
            selectedLocationID: location.id
        )

        model.requestPrimaryAction()

        #expect(model.presentedNotice?.title == "Требуется действие")
        #expect(model.presentedNotice?.message.contains("привязку аккаунта") == true)
    }

    @Test func readyLocationStillStartsDisconnected() {
        let location = LocationListItem(
            id: "ready",
            name: "Основная",
            detail: "olcRTC",
            isComplete: true,
            needsUserAction: false,
            isFromSubscription: true
        )
        let model = YPtunApplicationModel(
            locations: [location],
            selectedLocationID: location.id
        )

        #expect(model.connectionState == .disconnected)
        #expect(model.primaryActionTitle == "Подключить")
        #expect(model.presentedNotice == nil)
    }

    @Test func selectingAnotherLocationRestartsAnActiveTunnel() async {
        let first = LocationListItem(
            id: "first",
            name: "Первая",
            detail: "VLESS",
            isComplete: true,
            needsUserAction: false,
            isFromSubscription: false
        )
        let second = LocationListItem(
            id: "second",
            name: "Вторая",
            detail: "olcRTC",
            isComplete: true,
            needsUserAction: false,
            isFromSubscription: false
        )
        let controller = RecordingVPNController()
        let recorder = SelectionRecorder()
        let model = YPtunApplicationModel(
            locations: [first, second],
            selectedLocationID: first.id,
            vpnController: controller,
            locationSelection: { id in recorder.locationIDs.append(id) }
        )
        controller.state = .connected
        controller.onStateChange?(.connected)

        model.select(second)
        await model.waitForPendingSelection()

        #expect(recorder.locationID == second.id)
        #expect(model.selectedLocationID == second.id)
        #expect(controller.restartCount == 1)
        #expect(model.connectionState == .connected)
    }

    @Test func autoConnectAdvancesAfterAFailedCandidate() async {
        let first = LocationListItem(
            id: "first",
            name: "Первая",
            detail: "VLESS",
            isComplete: true,
            needsUserAction: false,
            isFromSubscription: false
        )
        let second = LocationListItem(
            id: "second",
            name: "Вторая",
            detail: "olcRTC",
            isComplete: true,
            needsUserAction: false,
            isFromSubscription: false
        )
        let controller = RecordingVPNController()
        controller.waitResults = [false, true]
        let recorder = SelectionRecorder()
        let model = YPtunApplicationModel(
            locations: [first, second],
            selectedLocationID: first.id,
            vpnController: controller,
            locationSelection: { id in recorder.locationIDs.append(id) }
        )

        model.autoConnectFastest()
        await model.waitForPendingConnection()

        #expect(recorder.locationIDs == [first.id, second.id])
        #expect(controller.startCount == 1)
        #expect(controller.restartCount == 1)
        #expect(controller.stopCount == 1)
        #expect(model.selectedLocationID == second.id)
        #expect(model.connectionState == .connected)
        #expect(!model.isAutoConnecting)
    }
}
