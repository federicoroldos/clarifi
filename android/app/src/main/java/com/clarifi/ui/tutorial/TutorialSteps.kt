package com.clarifi.ui.tutorial

/**
 * The tour, in order.
 *
 * One line per step. A tour is read standing up, mid-tap, so anything longer than
 * a sentence goes unread and only makes the card big enough to cover what it is
 * pointing at.
 *
 * Most steps are a tap on the real control. The ones that would take over the
 * screen are marked [TutorialStep.suppressAction], so the highlight advances and
 * nothing else happens; the ones that move you to the next screen are left alone,
 * because that is how the tour travels.
 */
internal val TutorialSteps: List<TutorialStep> = listOf(
    TutorialStep(
        target = null,
        title = "Getting started",
        body = "Let's learn the basics.",
        advance = Advance.Confirm,
        confirmLabel = "Start",
    ),
    TutorialStep(
        target = TutorialTarget.Fab,
        title = "Log your movements",
        body = "Expenses, income and transfers are recorded pressing the + button.",
        advance = Advance.Tap,
        suppressAction = true,
    ),
    TutorialStep(
        target = TutorialTarget.NavActivity,
        title = "Check your movements",
        body = "Every movement is recorded in Activity.",
        advance = Advance.Tap,
    ),
    TutorialStep(
        target = TutorialTarget.TxnRow,
        title = "Tap to edit",
        body = "Tap any entry to modify it.",
        advance = Advance.Tap,
        suppressAction = true,
    ),
    TutorialStep(
        target = TutorialTarget.TxnRow,
        title = "Swipe to delete",
        body = "Swipe any entry to the left to delete it.",
        advance = Advance.Gesture,
    ),
    TutorialStep(
        target = TutorialTarget.NavFixed,
        title = "Recurring movements",
        body = "Add and manage recurring expenses or incomes.",
        advance = Advance.Tap,
    ),
    TutorialStep(
        target = TutorialTarget.FixedApply,
        title = "Log its payment",
        body = "Tap the button to log the execution of that recurring payment.",
        advance = Advance.Tap,
        suppressAction = true,
    ),
    TutorialStep(
        target = TutorialTarget.NavScan,
        title = "Scanning",
        body = "Scan a receipt and have AI fill in the movement register form.",
        advance = Advance.Tap,
    ),
    TutorialStep(
        target = TutorialTarget.Menu,
        title = "Other functionality",
        body = "Tap the icon to access Statement imports, settings and more.",
        advance = Advance.Tap,
    ),
    TutorialStep(
        target = TutorialTarget.DrawerSettings,
        title = "Settings",
        body = "Here you can set your AI API key, manage your exports and cloud sync.",
        advance = Advance.Tap,
    ),
)
