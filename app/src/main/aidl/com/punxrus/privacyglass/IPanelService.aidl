package com.punxrus.privacyglass;

// Runs inside a Shizuku "user service" process with shell (adb) privileges.
interface IPanelService {
    // Shizuku requires this exact transaction code for destroy().
    void destroy() = 16777114;

    // Returns null on success, or an error message.
    String setDisplayPower(int displayId, boolean on) = 1;
}
