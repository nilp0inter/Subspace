# Subspace: Brand Visual Design Document

## 1. Brand Identity & Philosophy
**Concept:** Starfleet Field Operations.
Subspace is not a glossy consumer toy; it is a ruggedized, professional communication terminal. The visual identity bridges the gap between the durable, utilitarian design of a trucker’s CB radio and the clean, optimistic, purposeful interface of Starfleet field equipment. It is designed to act as a "base station" monitor for a handheld PTT device, prioritizing instant glanceability, high contrast, and clear state communication.

## 2. Color Palette

The app features two distinct modes: **Night Ops** (Dark) and **Daylight Starfleet** (Light).

### Dark Theme: "Night Ops / Deep Space"
Designed for low-light environments, mimicking the glowing terminals of a starship bridge at night.
*   **Background (The Void):** `#0B0F14` (Deep, matte charcoal-black. Absorbs light to keep the screen dim).
*   **Surface (Hull Plating):** `#1A1F26` (Slightly lighter charcoal for cards and UI elements).
*   **Primary Accent (Subspace Cyan):** `#00E5FF` (Used for active listening, scanning, and primary UI highlights. Glows against the dark background).
*   **Secondary Accent (Alert Amber):** `#FFB300` (Used exclusively for transmitting/speaking states and warnings. High visibility).
*   **Text Primary:** `#E0E6ED` (Crisp off-white).
*   **Text Secondary:**`#8B98A5` (Muted slate grey).

### Light Theme: "Daylight Starfleet"
Designed for bright, daytime environments, reminiscent of a starship hull bathed in Earth's sunlight.
*   **Background (Hull White):** `#F4F4F0` (Warm, sunlit off-white, avoiding harsh pure white).
*   **Surface (Deck Plating):** `#FFFFFF` (Pure white for raised cards and active elements).
*   **Primary Accent (Command Gold):** `#FFC107` (Warm, bright yellow-gold for primary actions and active states).
*   **Secondary Accent (Sciences Blue):** `#2D6CDF` (Muted Starfleet blue for informational links and secondary indicators).
*   **Text Primary:** `#1C1C1C` (Deep charcoal, not pure black, to reduce eye strain).
*   **Text Secondary:** `#5A5A5A` (Medium grey).

## 3. Typography

The typography must be highly legible at a glance, with a technical, geometric edge that feels slightly futuristic without being a video game font.

*   **Primary Font (Headers & UI): Chakra Petch**
    *   *Why:* It has distinct, geometric angles that evoke sci-fi terminals and technical readouts, but remains incredibly clean and readable. It gives the app its "field equipment" personality.
*   **Secondary Font (Body & Transcripts): Inter**
    *   *Why:* The ultimate neutral, highly legible sans-serif. It gets out of the way, ensuring that AI transcripts and longer text blocks are easy to read in any lighting.

## 4. Logo & Iconography

**The Logo: "The Analog-to-Digital Wave"**
The primary logo mark is a continuous horizontal line. On the left side, it forms a smooth, rolling analog sine wave (representing your voice and the radio frequency). As the line moves to the right, the waves sharpen and flatten into rigid, binary square waves (representing the digital AI processing and multiplexing).

**Iconography Style:**
*   **Line-art based:** Icons should be constructed from consistent stroke widths (2px).
*   **Rounded corners:** To maintain the approachable, optimistic Starfleet feel, all icons and UI containers should have slightly rounded corners (4px to 8px radius), avoiding sharp, aggressive points.
*   **State-driven:** Icons should not just be static; they should animate. The "mic" icon, for example, should pulse subtly when listening and pulse rapidly when transmitting.

## 5. UI/UX Principles

Because the user is interacting primarily via a physical handheld PTT device, the phone screen acts as a status monitor.

*   **Glanceability First:** The user should be able to look at the screen from arm's length and instantly know: *Am I connected? Am I transmitting? Who is listening?*
*   **State-Driven UI:** The entire screen's accent color shifts based on the PTT state.
    *   *Idle:* Dimmed UI, muted colors.
    *   *Listening (PTT Pressed):* Screen glows Cyan (Dark) or Gold (Light). A large, central waveform animates.
    *   *Thinking:* The waveform morphs into a spinning, geometric loader.
    *   *Speaking (AI Responding):* Screen glows Amber (Dark) or Blue (Light). The waveform animates in reverse.
*   **The "Channel" Metaphor:** The main screen displays large, touch-friendly "Channel" blocks (e.g., "Local LLM", "OpenAI", "STT Multiplexer"). The active channel is highlighted, and the user can switch channels via hardware buttons on the radio, with the UI updating instantly to reflect the active route.

## 6. Hardware Synergy

The UI is designed to complement a trucker-style handheld radio.
*   **Battery & Signal Indicators:** Placed prominently at the top, styled like a radio’s signal bars.
*   **PTT Visual Confirmation:** When the physical PTT button is pressed, the app doesn't just start recording; it flashes a large, full-screen border in the Transmit color to confirm the hardware input was registered, mimicking the "squelch" break of a real radio.
