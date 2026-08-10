// Reproduces the setup page's visibility logic exactly as the adapter runs it,
// so the "Done!" bug can be demonstrated and then proven fixed without a device.

enum class PageState { COMPLETE, INCOMPLETE, UNDEFINED }
enum class ButtonState { BUTTON_ACTION_COMPLETE, BUTTON_ACTION_INCOMPLETE, BUTTON_ACTION_UNDEFINED }

class PageButton(val titleId: Int, val state: () -> ButtonState)
class SetupPage(val buttons: List<PageButton>, val pageSteps: () -> PageState)

/** Mirror of SetupAdapter.SetupPageViewHolder. */
class ViewHolder(val page: SetupPage) {
    var containerVisible = true
    var confirmationVisible = false
    var buttonsCreated = 0
    val disabled = mutableSetOf<Int>()

    private fun hasActionableButton(): Boolean =
        page.buttons.any { it.state() != ButtonState.BUTTON_ACTION_COMPLETE }

    fun onStepCompleted(id: Int, pageFullyCompleted: Boolean) {
        if (pageFullyCompleted && !hasActionableButton()) {
            containerVisible = false
            confirmationVisible = true
        }
        if (id != 0 && buttonsCreated > 0) disabled.add(id)
    }

    fun bind() {
        // The fix: reset recycled state and clear old children first.
        buttonsCreated = 0
        disabled.clear()
        containerVisible = true
        confirmationVisible = false

        if (page.pageSteps() == PageState.COMPLETE) onStepCompleted(0, true)
        if (page.pageSteps() != PageState.COMPLETE) {
            for (b in page.buttons) {
                buttonsCreated++
                if (b.state() == ButtonState.BUTTON_ACTION_COMPLETE) onStepCompleted(b.titleId, false)
            }
        }
    }
}

/** Mirror of SetupFragment.checkForButtonState. */
fun checkForButtonState(page: SetupPage, vh: ViewHolder) {
    page.buttons.forEach {
        if (it.state() == ButtonState.BUTTON_ACTION_COMPLETE) vh.onStepCompleted(it.titleId, false)
        if (page.pageSteps() == PageState.COMPLETE) vh.onStepCompleted(0, true)
    }
}

var failures = 0
fun check(cond: Boolean, what: String) {
    println(if (cond) "  ok    $what" else "  FAIL  $what".also { failures++ })
}

const val FOLDER = 1; const val KEYS = 2; const val FIRMWARE = 3; const val GAMES = 4

fun dataPage(sharing: Boolean, keys: Boolean, firmware: Boolean, games: Boolean,
             pageSteps: () -> PageState): SetupPage =
    SetupPage(
        listOf(
            PageButton(FOLDER) { if (sharing) ButtonState.BUTTON_ACTION_COMPLETE else ButtonState.BUTTON_ACTION_UNDEFINED },
            PageButton(KEYS) { if (keys) ButtonState.BUTTON_ACTION_COMPLETE else ButtonState.BUTTON_ACTION_INCOMPLETE },
            PageButton(FIRMWARE) { if (firmware) ButtonState.BUTTON_ACTION_COMPLETE else ButtonState.BUTTON_ACTION_INCOMPLETE },
            PageButton(GAMES) { if (games) ButtonState.BUTTON_ACTION_COMPLETE else ButtonState.BUTTON_ACTION_INCOMPLETE }
        ), pageSteps)

fun main() {
    println("Setup page: the folder button must always be reachable\n")

    // v14 regression: page reported COMPLETE, so bind() created no buttons.
    println("v14 behaviour (pageSteps = COMPLETE) is no longer reachable:")
    run {
        val page = dataPage(false, false, false, false) { PageState.COMPLETE }
        val vh = ViewHolder(page); vh.bind()
        check(vh.buttonsCreated == 0, "COMPLETE still creates no buttons (upstream behaviour)")
        check(vh.containerVisible,
              "but the page no longer collapses, because the folder button is actionable")
    }

    // v15/v16: pageSteps is INCOMPLETE, so bind() does create the buttons...
    println("\nv16 behaviour (pageSteps = INCOMPLETE), fresh install:")
    run {
        val page = dataPage(false, false, false, false) { PageState.INCOMPLETE }
        val vh = ViewHolder(page); vh.bind()
        check(vh.buttonsCreated == 4, "all four buttons created")
        check(vh.containerVisible, "buttons visible")
    }

    // ...but the page is re-bound whenever ViewPager2 recreates it, and
    // checkForButtonState runs on every returning picker result.
    println("\nv16, user already has keys+firmware+games (upgrade install):")
    run {
        val page = dataPage(false, true, true, true) { PageState.INCOMPLETE }
        val vh = ViewHolder(page); vh.bind()
        check(vh.containerVisible, "container still visible")
        check(vh.buttonsCreated == 4, "folder button among them")
        check(!vh.disabled.contains(FOLDER), "folder button NOT disabled - it is still needed")
    }

    // The real regression path: any caller that passes pageFullyCompleted=true
    // hides the whole container, folder button included.
    println("\nA 'fully completed' call must not hide a still-usable folder button:")
    run {
        val page = dataPage(false, true, true, true) { PageState.COMPLETE }
        val vh = ViewHolder(page); vh.bind()
        checkForButtonState(page, vh)
        check(vh.containerVisible,
              "container stays visible: the folder button is still actionable")
    }

    // THE ACTUAL CAUSE. ViewPager2 is a RecyclerView: one ViewHolder is reused
    // for several pages. The permissions page reports COMPLETE once
    // notifications are granted, which hides the container and shows "Done!".
    // bind() never restores either, so the next page bound into that same
    // holder inherits a hidden container - and its buttons are invisible even
    // though they were created.
    println("\nViewHolder recycled from the permissions page to the data page:")
    run {
        val permissions = SetupPage(emptyList()) { PageState.COMPLETE }
        val vh = ViewHolder(permissions)
        vh.bind()
        check(!vh.containerVisible && vh.confirmationVisible, "permissions page shows \"Done!\"")

        // Same holder, now bound to the data page.
        val data = dataPage(false, false, false, false) { PageState.INCOMPLETE }
        val recycled = ViewHolder(data)
        recycled.containerVisible = vh.containerVisible       // state carried over
        recycled.confirmationVisible = vh.confirmationVisible
        recycled.bind()
        check(recycled.buttonsCreated == 4, "data page did create its buttons")
        check(recycled.containerVisible,
              "container visible again - THIS IS THE BUG when it fails")
        check(!recycled.confirmationVisible,
              "\"Done!\" cleared - THIS IS THE BUG when it fails")
    }

    // Rebinding must not stack duplicate buttons either.
    println("\nSame page bound twice (rotation, notifyDataSetChanged):")
    run {
        val page = dataPage(false, false, false, false) { PageState.INCOMPLETE }
        val vh = ViewHolder(page)
        vh.bind()
        vh.bind()
        check(vh.buttonsCreated == 4, "still four buttons, not eight")
    }

    println("\nA genuinely finished page may still collapse:")
    run {
        val page = dataPage(true, true, true, true) { PageState.COMPLETE }
        val vh = ViewHolder(page); vh.bind()
        checkForButtonState(page, vh)
        check(!vh.containerVisible, "nothing actionable left, so \"Done!\" is correct here")
    }

    println("\n${if (failures == 0) "ALL TESTS PASSED" else "THERE ARE FAILURES"}")
    if (failures != 0) kotlin.system.exitProcess(1)
}
