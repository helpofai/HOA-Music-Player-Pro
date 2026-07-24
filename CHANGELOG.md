# Changelog - HOA Music Player Pro Audio Engine

All notable changes to the professional audio DSP (Digital Signal Processing) suite are documented here.

## [Billing Library Update - July 2026] (v6.0.0)

### Changed
- **Google Play Billing**: Upgraded Google Play Billing Library to latest stable version 9.1.0 to comply with Google's upcoming August 2026 mandate.
- **Binaural Spatializer**: Fixed Woodworth ITD math and inverted ILD panning bugs. 3D spatial panning is now mathematically accurate.
- **Mastering Limiter**: Fixed a bug where the Instrument Exciter's brick-wall limiter was always active, restoring full dynamic range when effects are bypassed.
- **Bass Envelope**: Corrected attack/release time constants in BassBoostProcessor for massive kinetic punch.

## [Surgical Bass Engine & Stereo Imaging Overhaul - June 2026] (v5.2.0)

### Changed
- **BassBoostProcessor v9.1**: Replaced imprecise 8-stage single-pole filter with a proper 4-stage biquad cascade (Butterworth Q=0.707) for sharp, clean 90Hz sub isolation with no phase smearing.
- **StereoProcessor**: Removed the 150Hz mono-bass crossover — full-range L/R stereo image is now preserved end-to-end. Bass effects stay where the mix placed them.
- **InstrumentExciterProcessor**: Fixed `isActive()` — processor only engages when clarity or strength is non-zero. No more always-on compression degrading audio quality at default settings.
- **ReverbProcessor**: Added output clamping to prevent clipping at high wet mix levels.
- **Mud-carving reduced**: From 45% to 20% max — less aggressive, retains bass body and warmth.

### Added
- **Progressive saturation**: Smooth tanh warmth from strength=0, no sudden gate at 30%.
- **Peak-hold impact envelope**: Clean transient extraction with 6ms hold + 3ms attack / 60ms release for punchier bass dynamics.

### Removed
- **Mono-bass sum from BassBoost**: Sub-bass now preserves full stereo L/R separation.
- **Always-on limiter from InstrumentExciter**: Soft-clip limiter is only active when the processor is enabled.

## [HRTF Binaural Engine & Audio Refinements - June 2026] (v5.1.0)

### Added
- **Real HRTF Binaural Processor**: Replaced the old delay-based "8D" mode with a proper parametric HRTF model using Woodworth ITD formula, frequency-dependent ILD head shadowing, and elevation notch filters for true 360° spatial audio.
- **3D Binaural Spatial Slider**: New percentage-based control in Settings → Audio for precise spatial strength adjustment.
- **AdMob MediaView Compliance**: Fixed `MediaView` minimum size to 120×120dp to satisfy Google's native ad video policy.

### Changed
- **StereoProcessor Cleanup**: Removed the simulated delay-matrix "8D" spatial mode — spatial processing is now handled exclusively by the dedicated BinauralProcessor with proper HRTF.
- **Audio Presets Updated**: "3D Spatial (8D)" preset now correctly enables the new BinauralProcessor at 70% strength.
- **Version bump**: 5.0.0 → 5.1.0

## [Monetization Suite & Android 15 Compliance - June 2026] (v5.0.0)

### Added
- **Rewarded Video Ads**: Users can watch a short ad to try Pro features directly from Settings.
- **Extended Ad Network**: New ad placements across Home (banner + native in suggestions, inline banner sections), Album Details, Artist Details, and Playlist Details pages.
- **Smart Banner Refresh**: Adaptive banner sizing and automatic retry logic for maximum ad fill rate.
- **Session-Based Interstitials**: Context-aware fullscreen ads on natural break points (app resume after extended idle).

### Fixed
- **AdView Memory Leak**: Fixed `AdView.destroy()` never being called on RecyclerView item recycling — eliminated accumulated ad views in memory.
- **Pro Detection Race**: Fixed a brief window where ads could show to Pro users before BillingManager resolved purchase status.
- **Native Ad Lifecycle**: Native ads on the Now Playing album cover now properly destroy previous ads on swipe, preventing memory leaks and stale-load callbacks.
- **Stale Banner Retries**: Cancelled pending retry handlers when a recycled banner is rebound, preventing ad loads on destroyed AdViews.
- **Android 15 API Compliance**: Properly guarded deprecated `window.navigationBarColor`/`statusBarColor` assignments with API 35+ checks to eliminate deprecation warnings.

### Improved
- **Ad Placement Density**: Banner ads now show every 3 sections on the Home page, every 3-5 items in library lists, and as native placements in the player and suggestions.

## [Android 15 Edge-to-Edge Support - July 2026] (v4.6.1)

### Added
- **Edge-to-Edge Display**: Implemented modern `enableEdgeToEdge()` API for seamless, immersive UI on Android 15+.
- **Inset Handling**: Automated handling of system bars to ensure UI elements never overlap with system navigation.
- **High-Fidelity Crossfade Engine**: Rebuilt the crossfade system using dual **HoaExoPlayer** instances for gapless transitions.
- **Bass Engine Overdrive (v11.0)**: Increased kinetic punch multiplier for physical, aggressive sub-bass response.
- **Precision Audio Sliders**: Added fine-tuning buttons for 1% increment professional mastering control.

### Fixed
- **Slider Stability**: Fixed a critical "Maximum less than Minimum" crash in the progress slider.
- **Translation Fix**: Resolved string formatting issues in Persian (Farsi) translation.
- **Android 15 Migration**: Successfully migrated away from deprecated window styling APIs.

## [Professional Mastering Rebuild - June 2026] (v3.4.0)

### Added
- **Apex Kinetic Bass Engine (v9.0)**: Rebuilt for surgical punch and massive raw power using 8th-Order Linkwitz-Riley isolation and 250Hz spectral mud-carving.
- **True 8D Spherical HRTF Matrix**: Implemented physical Pinna-filtering cues for a 360-degree spherical soundstage.
- **Universal Horizon Stadium Depth**: Merged long-distance virtual reflections with the directional 8D matrix for an infinite stadium feel.
- **Discrete Band Mastering**: Individual limiters for each audio band, ensuring heavy bass never "ducks" vocals.
- **Low-Bitrate Audio Restoration**: Spectral high-end reconstruction to rebuild lost detail in low-quality MP3s.

## [AdMob Integration & UI Suite - May 2026] (v3.3.0)

### Added
- **Professional AdMob Integration**: Integrated banner and native ads across all library sections.
- **Native Player Ads**: Material 3 native ad experience that replaces the album cover on the Now Playing screen.
- **Centralized Ads Management**: New `AdsManager` for dynamic ad control and "Remove Ads" shortcuts.

## [High-Res Audio Engine Pro - April 2026] (v3.2.0)

### Added
- **High-Res Audio Engine Pro**: Major upgrade to the spatial audio suite with Interaural Level Difference (ILD) filtering.
- **Diamond Air & Clarity**: New ultra-high frequency exciter (>12kHz) for vocal sheen.
- **Mastering-Grade Bass**: Phase-aligned enhancement with adaptive hardware-style saturation.
- **In-App Privacy Policy**: Professional themed privacy policy in the About section.

## [Advanced Audio Suite - March 2026] (v3.1.0)

### Added
- **Holophonic 6-Tap Binaural Engine**: 3D soundstage using 6 independent virtual reflections.
- **Kinetic Bass Transient Shaper**: Dynamic transient shaping for physical drum and bass punch.
- **Dual-Band Sub-Bass Processing**: Independent weight and thump management (45Hz vs 90Hz).
- **Analog Soft Limiter**: Final output stage with `tanh` hyperbolic tangent function for warm saturation.

## [Legacy Support Cleanup - February 2026] (v3.0.0)

### Improved
- **Notification Suite**: Removed legacy notification system in favor of modern Android Media controls.
- **Core Stability**: Resolved repeat mode logic errors and updated Google Play Billing to v7.0.

## [ExoPlayer Integration - December 2025] (v2.9.0)

### Changed
- **Audio Engine**: Migrated entire playback system from MediaPlayer to **ExoPlayer** for superior performance and gapless support.

## [Android 15 Readiness - November 2025] (v2.8.0)

### Added
- **Target SDK 35**: Initial compatibility work for Android 15.
- **Playlist Search**: Enabled search functionality within individual playlists.

## [UI/UX Refinement - October 2025] (v2.5.0)

### Improved
- **Android 13 Features**: Implemented Photo Picker and Per-App Language preferences.
- **Playlist UI**: Minor redesign of the Playlist details screen for better usability.

## [Landscape & Tablet Optimization - August 2025] (v2.2.0)

### Added
- **Navigation Rail**: New UI layout for landscape and large-screen devices.
- **Slider Control**: Replaced old seekbars with modern Material 3 Sliders.

## [Material You & Theming - June 2025] (v2.0.0)

### Added
- **Material You Engine**: Support for dynamic wallpaper-based accent colors.
- **Edge-to-Edge Initial Support**: Early implementation of immersive UI patterns.

## [Android Auto & Widgets - May 2025] (v1.8.0)

### Added
- **Android Auto**: Full support for automotive head units.
- **Circle Widget**: New customizable home screen widget.

## [Tag Editor & Visuals - March 2025] (v1.5.0)

### Added
- **Artwork Editing**: In-app album art selection and tagging.
- **Snowfall Effect**: Interactive visualizer overlay.

## [Pro Architecture Rebuild - February 2025] (v1.3.0)

### Added
- **MVVM Refactor**: Complete architectural migration for improved performance and testability.
- **Jetpack Suite**: Integrated Navigation, Room, and Lifecycle components.

## [Initial Professional Release - January 2025] (v1.1.1)

### Added
- **HOA Music Player Pro**: Official launch of the professional audio suite.
- **32-bit DSP Path**: Initial high-fidelity signal path implementation.
