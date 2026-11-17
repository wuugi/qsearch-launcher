Home screen widgets know their size again.

### Fix

- Some widgets (like Naver Pay) looked fine at first, then after a while redrew with their content pushed to the edges and parts missing. The launcher never told widgets how big their space was, so a widget that refreshed itself guessed. Widgets now get their real size, so they keep their layout after they refresh.

### Install

Download `QSearchLauncher-0.5.9.apk` below and install it over 0.5.8. Your settings stay.

This release adds no new permissions. The permission list is in [docs/PRIVACY.md](https://github.com/wuugi/qsearch-launcher/blob/main/docs/PRIVACY.md) (Korean).
