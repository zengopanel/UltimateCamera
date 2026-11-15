# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

People using an Android phone camera who want an immediate point-and-shoot experience with optional professional controls.

## Product Purpose

Capture and save still photos and video using the phone's camera hardware, with quick access to the controls that camera supports.

## Positioning

An Android camera app that combines a streamlined capture surface with hardware-discovered manual controls and modes, rather than advertising unavailable features.

## Operating Context

The app is used directly from a phone while composing photos or video. The viewfinder and shutter must remain primary; settings should be reachable without obscuring the scene.

## Capabilities and Constraints

- Native Android app using Camera2; minimum Android version is 10.
- Feature visibility and settings depend on the active camera's reported capabilities.
- Portrait, Night, HDR, manual controls, video sizes/frame rates, RAW, and stabilization must use supported APIs or remain unavailable.
- Video stabilization is hardware OIS/EIS only; software stabilization is not implemented.

## Brand Commitments

- Keep the product name Ultimate Camera.
- Use a premium dark camera interface with its own identity, informed by flagship camera usability without copying another app.

## Evidence on Hand

- The existing project source and its Camera2 hardware analyzer.
- No target device or camera hardware has been specified for device-level validation.

## Product Principles

- Show the scene before secondary controls.
- Make capture actions immediate and predictable.
- Do not imply support for hardware or processing that is absent.
- Keep professional controls optional and context-sensitive.
