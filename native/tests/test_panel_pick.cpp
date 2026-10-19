#include "panel_pick.h"

#include <cassert>
#include <iostream>

using namespace vrplayer;

static void TestNoHit() {
    assert(PickNearestPanel(-1.0f, -1.0f, -1.0f) == Panel::None);
    assert(PickNearestPanel(0.0f, 0.0f, 0.0f) == Panel::None);
    std::cout << "[PASS] TestNoHit\n";
}

static void TestSinglePanel() {
    assert(PickNearestPanel(2.0f, -1.0f, -1.0f) == Panel::Ui);
    assert(PickNearestPanel(-1.0f, 2.0f, -1.0f) == Panel::Controls);
    assert(PickNearestPanel(-1.0f, -1.0f, 2.0f) == Panel::Browser);
    std::cout << "[PASS] TestSinglePanel\n";
}

static void TestBrowserBehindControlsIsNotHit() {
    assert(PickNearestPanel(-1.0f, 1.5f, 3.0f) == Panel::Controls);
    assert(PickNearestPanel(1.0f, -1.0f, 3.0f) == Panel::Ui);
    std::cout << "[PASS] TestBrowserBehindControlsIsNotHit\n";
}

static void TestBrowserInFrontWins() {
    assert(PickNearestPanel(3.0f, 2.5f, 1.5f) == Panel::Browser);
    std::cout << "[PASS] TestBrowserInFrontWins\n";
}

static void TestTieKeepsPriority() {
    assert(PickNearestPanel(2.0f, 2.0f, 2.0f) == Panel::Browser);
    assert(PickNearestPanel(2.0f, 2.0f, -1.0f) == Panel::Controls);
    std::cout << "[PASS] TestTieKeepsPriority\n";
}

static void TestModalPanelPick() {
    assert(PickNearestPanel(-1.0f, -1.0f, -1.0f, 2.0f) == Panel::Modal);
    assert(PickNearestPanel(3.0f, 2.5f, -1.0f, 1.8f) == Panel::Modal);
    assert(PickNearestPanel(-1.0f, 1.5f, -1.0f, 2.0f) == Panel::Controls);
    assert(PickNearestPanel(2.0f, 2.0f, 2.0f, 2.0f) == Panel::Browser);
    assert(PickNearestPanel(2.0f, 2.0f, -1.0f, 2.0f) == Panel::Modal);
    std::cout << "[PASS] TestModalPanelPick\n";
}

int main() {
    TestNoHit();
    TestSinglePanel();
    TestBrowserBehindControlsIsNotHit();
    TestBrowserInFrontWins();
    TestTieKeepsPriority();
    TestModalPanelPick();
    return 0;
}
