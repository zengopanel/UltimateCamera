---
name: Ultimate Camera
description: A scene-first Android camera instrument with capability-aware capture controls.
colors:
  primary: "#E2C58A"
  primary-deep: "#A98548"
  neutral-black: "#000000"
  neutral-ink: "#090909"
  neutral-control: "rgba(21, 21, 20, 0.8)"
  neutral-white: "#FFFFFF"
  text-muted: "#B4B0A8"
  recording: "#FF4D58"
typography:
  display:
    fontFamily: "sans-serif-light"
    fontSize: "76sp"
    fontWeight: 300
  body:
    fontFamily: "sans-serif-medium"
    fontSize: "13sp"
    fontWeight: 500
  label:
    fontFamily: "sans-serif-medium"
    fontSize: "10sp"
    fontWeight: 500
    letterSpacing: "0.14em"
rounded:
  circle: "50%"
  pill: "20dp"
  mode-rail: "18dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "24dp"
components:
  camera-control:
    backgroundColor: "{colors.neutral-control}"
    textColor: "{colors.neutral-white}"
    rounded: "{rounded.circle}"
    size: "48dp"
  photo-shutter:
    backgroundColor: "{colors.neutral-white}"
    rounded: "{rounded.circle}"
    size: "82dp"
  recording-shutter:
    backgroundColor: "{colors.recording}"
    rounded: "{rounded.circle}"
    size: "82dp"
  mode-selected:
    backgroundColor: "rgba(51, 43, 30, 0.27)"
    textColor: "{colors.primary}"
    rounded: "{rounded.mode-rail}"
    height: "48dp"
  zoom-selector:
    backgroundColor: "rgba(0, 0, 0, 0.5)"
    textColor: "{colors.neutral-white}"
    rounded: "{rounded.pill}"
    padding: "5dp"
  pro-readout:
    textColor: "{colors.neutral-white}"
    rounded: "8dp"
    padding: "6dp"
---

# Design System: Ultimate Camera

## Overview

**Creative North Star: "The Calibrated Instrument"**

The interface treats the phone as a capable camera instrument: the live scene is the work surface, and controls appear only where they help compose, expose, or capture it. A restrained obsidian field lets the viewfinder lead; satin brass marks active choices, pearl carries essential information, and recording red is reserved for video state.

Controls are direct, compact, and capability-aware. The system uses familiar Android touch behavior while drawing its custom camera controls in a precise, tactile visual language. The look is informed by flagship camera usability, not copied from another camera app.

**Key Characteristics:**
- Viewfinder-first, full-screen composition.
- Satin-brass active states on a dark neutral field.
- Recording red reserved for video capture state.
- Modes and settings reflect capabilities reported by Camera2.

## Colors

The palette is restrained: neutral black and pearl support one warm brass accent, with recording red reserved for active video.

### Primary
- **Satin Brass:** Used for selected modes, active control icons, focus marks, and the exposure ruler's active position.
- **Deep Brass:** A secondary warm accent for restrained emphasis.

### Neutral
- **Obsidian:** The full-screen base behind the preview and lower control deck.
- **Ink:** A near-black value used in the upper scrim.
- **Control Glass:** A translucent charcoal surface for floating top controls.
- **Pearl:** Primary icon and shutter contrast against the viewfinder and control surfaces.
- **Muted Pearl:** Secondary labels and inactive mode text.

### Named Rules
**The One Signal Rule.** Brass means an active selection; recording red means video is recording. Do not use either as decoration.

## Typography

**Display Font:** Android `sans-serif-light` (Roboto system family)

**Body Font:** Android `sans-serif-medium` (Roboto system family)

**Label Font:** Android `sans-serif-medium`; tracked uppercase labels are used on compact camera controls.

**Character:** System typography keeps camera status legible at a glance. Large numerals are reserved for the capture timer; labels stay compact and high-contrast.

### Hierarchy
- **Display** (light, 76sp): The animated self-timer countdown.
- **Body** (medium, 13sp): Transient capture messages and recording status.
- **Label** (medium, 10–13sp, tracked uppercase where used): Pro parameter names, mode names, and compact control readouts.

### Named Rules
**The Readout Rule.** Use tabular-feeling, concise values for exposure, zoom, timer, and recording state; do not turn technical values into decorative copy.

## Layout

The portrait camera surface is edge-to-edge. A large live viewfinder fills the screen; compact controls sit in a translucent top band, while zoom, optional Pro controls, mode selection, and the shutter occupy a lower control deck. The control deck reserves bottom space for system navigation insets. The mode rail caps each item at 76dp and adapts to the available width.

Interactive camera controls use 48dp touch targets. The viewfinder remains unobstructed except for purposeful composition, focus, exposure, and recording overlays. Pro controls are shown only when the camera exposes usable parameters.

## Elevation & Depth

Depth comes from translucent charcoal surfaces and top/bottom scrims over the live image, not card stacks or drop shadows. The upper and lower scrims protect icon and control contrast while leaving the scene visible. The focus ring and grid sit directly over the preview.

### Named Rules
**The Scene-First Rule.** Keep chrome translucent and sparse; never place an opaque decorative panel over the viewfinder.

## Shapes

Camera controls use circular silhouettes, with capsule tracks for zoom and mode selection. The shutter is an 82dp circle; while recording, its inner disc becomes a compact rounded square. Pro parameters use simple text chips instead of nested cards. The composition grid and focus ring use thin, precise strokes.

## Components

### Camera controls
- **Shape:** 48dp circular touch targets with translucent charcoal backing.
- **Default:** Pearl icon on the scene; active controls use satin brass.
- **Disabled:** Reduced opacity; unavailable stabilization remains explicitly identified as unsupported.
- **Feedback:** Short press compression and a restrained spring release.

### Shutter
- **Photo:** White outer ring and white center disc.
- **Video:** Recording-red center disc; during recording the center becomes a rounded stop square.
- **Feedback:** The inner shape compresses on press and returns with a brief spring.

### Mode rail
- **Shape:** 48dp-high rounded capsule with a sliding selected field and a small brass position mark.
- **State:** Selected mode is brass; inactive modes are muted pearl.
- **Behavior:** Modes are limited to those supported by the active camera; accessibility scroll actions move between modes.

### Zoom selector
- **Shape:** Translucent capsule with compact stop markers.
- **State:** A moving highlight follows live zoom; selected value carries the brass accent.
- **Behavior:** Presets animate between stops; pinch zoom remains available when the camera mode supports crop-region zoom.

### Pro readout
- **Shape:** Small centered chips over a 64dp exposure ruler.
- **State:** The selected ISO, shutter, focus, white-balance, or EV control is emphasized in brass.
- **Behavior:** The ruler responds to drag and fling, then settles on a real hardware-supported value.

## Do's and Don'ts

### Do:
- **Do** keep the active scene as the dominant visual content.
- **Do** show capture modes and settings only when the active camera reports support.
- **Do** use brass for active state and recording red only for recording state.
- **Do** make focus, exposure, and timer overlays appear only when their action is available.

### Don't:
- **Don't** claim software video stabilization; only advertised OIS/EIS requests are implemented.
- **Don't** show a failed camera extension as an active mode.
- **Don't** add decorative settings cards, gradients, or camera controls that do not change capture behavior; functional contrast scrims are allowed.
