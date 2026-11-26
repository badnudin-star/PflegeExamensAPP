import XCTest

final class RankingFlowTests: XCTestCase {
    func testSyntheticRankingFlow() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-synthetic-ranking-ui"]
        app.launch()
        let name = app.textFields["ranking.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 20))
        name.tap(); name.typeText("Testperson")
        app.navigationBars.firstMatch.tap()
        let register = app.buttons["ranking.register"]
        show(register, in: app); register.tap()
        let confirm = app.buttons["Anmeldung senden"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.tap()
        let load = app.buttons["ranking.load"]
        XCTAssertTrue(load.waitForExistence(timeout: 10)); show(load, in: app); load.tap()
        for _ in 0..<50 {
            let option = app.buttons["ranking.option.0"]
            XCTAssertTrue(option.waitForExistence(timeout: 10)); show(option, in: app); option.tap()
            let next = app.buttons["ranking.next"]
            XCTAssertTrue(next.waitForExistence(timeout: 10)); show(next, in: app); next.tap()
        }
        let submit = app.buttons["ranking.submit"]
        XCTAssertTrue(submit.waitForExistence(timeout: 10)); show(submit, in: app); submit.tap()
        let confirmSubmit = app.buttons["Einmalig abgeben"]
        XCTAssertTrue(confirmSubmit.waitForExistence(timeout: 5)); confirmSubmit.tap()
        let acknowledge = app.buttons["ranking.ack"]
        XCTAssertTrue(acknowledge.waitForExistence(timeout: 10)); show(acknowledge, in: app); acknowledge.tap()
        let deletion = app.buttons["ranking.delete"]
        show(deletion, in: app); deletion.tap()
        let confirmDelete = app.buttons["Konto löschen"]
        XCTAssertTrue(confirmDelete.waitForExistence(timeout: 5)); confirmDelete.tap()
        XCTAssertTrue(app.staticTexts["Die Löschung wurde bestätigt und der lokale Zugang entfernt."].waitForExistence(timeout: 10))
    }
    private func show(_ element: XCUIElement, in app: XCUIApplication) {
        if element.isHittable { return }
        for _ in 0..<8 {
            app.swipeUp()
            if element.isHittable { return }
        }
        for _ in 0..<8 {
            app.swipeDown()
            if element.isHittable { return }
        }
        XCTFail("Bedienelement nicht erreichbar: \(element.identifier)")
    }
}
