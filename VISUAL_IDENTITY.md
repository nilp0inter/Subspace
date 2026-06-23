# Subspace Visual Identity

## 1. Brand Identity & Philosophy

**Concept:** Starfleet Field Operations.

Subspace is not a glossy consumer toy; it is a ruggedized, professional
communication terminal for a programmable radio of voice channels. The visual
identity bridges the gap between the durable, utilitarian design of a trucker's
CB radio and the clean, optimistic, purposeful interface of Starfleet field
equipment. It is designed to act as a base-station monitor for a handheld PTT
device, prioritizing instant glanceability, high contrast, and clear state
communication.

## 2. Product Alignment

The product is hardware-first and voice-first. The phone screen supports the
live loop; it does not replace it. The UI should make the active channel,
connection state, transmission state, playback state, and pending message state
obvious from arm's length.

Channels are routes for voice tools. A channel may be an assistant, audio log,
transcription log, webhook, automation, third-party integration, or advanced
pipeline. The visual system must not imply that every channel is AI-backed,
synchronous, or request-response.

## 3. Color Palette

The app features two distinct modes: **Night Ops** (Dark) and **Daylight Starfleet** (Light).

### Dark Theme: Night Ops / Deep Space

Designed for low-light environments, mimicking the glowing terminals of a starship bridge at night.

- **Background (The Void):** `#0B0F14` (deep, matte charcoal-black; absorbs light to keep the screen dim).
- **Surface (Hull Plating):** `#1A1F26` (slightly lighter charcoal for cards and UI elements).
- **Primary Accent (Subspace Cyan):** `#00E5FF` (active channel, scanning, readiness, and primary UI highlights).
- **Secondary Accent (Alert Amber):** `#FFB300` (transmitting, warnings, and high-priority state changes).
- **Text Primary:** `#E0E6ED` (crisp off-white).
- **Text Secondary:** `#8B98A5` (muted slate grey).

### Light Theme: Daylight Starfleet

Designed for bright, daytime environments, reminiscent of a starship hull bathed in Earth's sunlight.

- **Background (Hull White):** `#F4F4F0` (warm, sunlit off-white, avoiding harsh pure white).
- **Surface (Deck Plating):** `#FFFFFF` (pure white for raised cards and active elements).
- **Primary Accent (Command Gold):** `#FFC107` (primary actions, active states, and selected channel state).
- **Secondary Accent (Sciences Blue):** `#2D6CDF` (informational links and secondary indicators).
- **Text Primary:** `#1C1C1C` (deep charcoal, not pure black, to reduce eye strain).
- **Text Secondary:** `#5A5A5A` (medium grey).

## 4. Typography

The typography must be highly legible at a glance, with a technical, geometric edge that feels slightly futuristic without being a video game font.

- **Primary Font (Headers & UI): Chakra Petch**
  It has distinct, geometric angles that evoke sci-fi terminals and technical readouts, but remains clean and readable. It gives the app its field-equipment personality.
- **Secondary Font (Body & Message Content): Inter**
  It is neutral and highly legible. It gets out of the way, ensuring transcripts, channel history, configuration text, and longer message content are easy to read in any lighting.

## 5. Logo & Iconography

**The Logo: The Analog-to-Routed Wave**

The primary logo mark is a continuous horizontal line. On the left side, it forms
a smooth, rolling analog sine wave representing voice and radio operation. As
the line moves to the right, it sharpens into structured digital segments
representing routing, processing, storage, and multiplexing across channels.

**Iconography Style:**

- **Line-art based:** Icons should be constructed from consistent stroke widths (2px).
- **Rounded corners:** To maintain the approachable, optimistic Starfleet feel, icons and UI containers should have slightly rounded corners (4px to 8px radius), avoiding sharp, aggressive points.
- **State-driven:** Icons should communicate live state. The mic icon can pulse subtly while armed and pulse rapidly while transmitting.

## 6. UI/UX Principles

Because the user is interacting primarily via a physical handheld PTT device, the phone screen acts as a status monitor.

- **Glanceability First:** The user should be able to look at the screen from arm's length and instantly know: connected state, active channel, transmission state, playback state, and pending message state.
- **State-Driven UI:** The screen's accent treatment should shift based on live operational state.
- **Idle:** Dimmed UI, muted colors, active channel visible.
- **Transmitting:** Screen glows Cyan (Dark) or Gold (Light). A large waveform confirms capture from the hardware PTT path.
- **Processing / Waiting:** The waveform becomes a geometric loader or route indicator when a channel is processing an outbound message.
- **Playback / Channel Response:** Screen glows Amber (Dark) or Blue (Light). The waveform animates as playable inbound audio or generated speech is heard.
- **Channel Metaphor:** The main screen displays large channel blocks such as Assistant, Audio Log, Webhook, Automation, or Integration. The active channel is highlighted, pending unheard messages are visible, and hardware navigation updates the UI immediately.

## 7. Hardware Synergy

The UI is designed to complement a trucker-style handheld radio.

- **Battery & Signal Indicators:** Placed prominently at the top, styled like radio signal bars when the relevant data exists.
- **PTT Visual Confirmation:** When the physical PTT button is pressed, the app does not just start recording; it flashes a large, full-screen border in the transmit color to confirm the hardware input was registered, mimicking the squelch break of a real radio.
- **Control Mode Feedback:** When the hardware enters control mode, the UI should visibly distinguish cursor movement from active-channel changes. The active channel changes only after confirmation.
- **History Feedback:** Channel history mode should make the selected historical message, playback state, and exit path clear without requiring touchscreen interaction.
