Backup now covers home widgets and every remaining setting, and the app helps you past Android's "Restricted setting" block.

### Backup and restore

- Widgets are now in the backup file: which app each widget comes from, the slot layout (widget stacks included) and each widget's height.
- The currency conversion switch and the "open with shortcut over other apps" switch are now in the backup file too. For the second one only your choice is restored. Android doesn't let an app restore the accessibility permission itself, so the app asks you to turn the service on again.
- On restore, each widget goes through the system "allow widget" dialog, and through its own setup screen if it has one. Widgets whose app isn't installed, or that you cancel, are skipped, and the app tells you how many.
- Settings inside a widget (for example a clock's city) belong to the widget's app and are not restored.
- The widgets in the file replace the widgets on your home screen. If none of them can be restored, your current widgets stay.
- One file restores at most 40 widgets. Import and export are disabled while a restore is running.
- Backup files from 0.5.0 still restore. 0.5.0 can read a 0.5.1 file but ignores the widgets and the two new switches.
- App usage history (which apps you open most) is still left out. It is a usage record, not a setting.

### Restricted settings

- On Android 13 and later, Android can block notification access for apps installed from a browser or file manager. When notification access is off, Settings now shows a row that explains "Allow restricted settings" and opens App info for you. The accessibility setup steps already had this.
- The README's install section has the same steps.

### Install

Download `QSearchLauncher-0.5.1.apk` below and install it over 0.5.0. Your settings stay.

The permission list is in [docs/PRIVACY.md](https://github.com/wuugi/qsearch-launcher/blob/main/docs/PRIVACY.md) (Korean).
