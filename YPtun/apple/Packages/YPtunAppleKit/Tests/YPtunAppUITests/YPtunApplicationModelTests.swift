import Testing
@testable import YPtunAppUI
import YPtunShared

@MainActor
struct YPtunApplicationModelTests {
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

    @Test func readyLocationDoesNotPretendThatTheTunnelConnected() {
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

        model.requestPrimaryAction()

        #expect(model.connectionState == .disconnected)
        #expect(model.presentedNotice?.title == "Подключение пока недоступно")
    }
}
