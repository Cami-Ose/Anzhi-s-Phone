package android.anzhi;

interface IAnzhiCoreService {
    /** Capture screenshot of the given display. Returns file descriptor to a temporary JPEG file,
     *  or null when the system refuses (secure/protected windows, missing READ_FRAME_BUFFER). */
    ParcelFileDescriptor captureScreen(int displayId);

    /** Inject a motion event (ACTION_DOWN, ACTION_MOVE, ACTION_UP) at (x,y) on displayId. */
    boolean injectInputEvent(int action, int x, int y, int displayId);

    /** Inject a key press/release event. */
    boolean injectKeyEvent(int keyCode, boolean down);

    /** Inject a swipe gesture from (x1,y1) to (x2,y2) over durationMs milliseconds. */
    boolean injectSwipe(int x1, int y1, int x2, int y2, int durationMs);

    /** Get the width of the default display in pixels. */
    int getDisplayWidth();

    /** Get the height of the default display in pixels. */
    int getDisplayHeight();

    /** Get the current display rotation (0/1/2/3). */
    int getRotation();

    /** Move a task to a specific display (for multi-display scenarios). */
    boolean moveTaskToDisplay(int taskId, int displayId);

    /** Read a system property. Returns defaultValue if not set. */
    String getSystemProperty(String key, String defaultValue);

    /** Get running task IDs. maxCount limits results, displayId filters by display. */
    int[] getRunningTasks(int maxCount, int displayId);

    /** Bring a task to the foreground. */
    boolean moveTaskToFront(int taskId);
}
