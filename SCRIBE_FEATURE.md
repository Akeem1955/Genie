# Scribe Profile Feature Implementation

## Overview
The Scribe Profile is a new tool profile for Genie that enables audio recording, transcription, and intelligent summarization. It supports two modes:
1. **General Mode**: Standard transcription with insights extraction
2. **Doctor Scribe Mode**: Medical transcription formatted as SOAP notes

## Architecture

### Profile System Integration
- Added `Scribe` enum to `ToolProfile.kt` with 4 dedicated tools
- Configured as a reactive profile (no planning phase, direct tool execution)
- Full-screen overlay UI similar to Teaching profile

### Data Models (`ScribeModels.kt`)
- `ScribeConfig`: Stores input/output language and mode selection
- `ScribeMode`: Enum for GENERAL vs DOCTOR_SCRIBE
- `TranscriptionResult`: Raw transcription data
- `GeneralInsights`: Structured summary, key points, action items
- `SoapNote`: Medical SOAP format (Subjective, Objective, Assessment, Plan)
- `ScribeResult`: Sealed class for different result types
- `ScribeSessionStore`: In-memory session management

### UI Components (`ScribeRecordingUI.kt`)

#### UI States
1. **Idle**: Floating record button with pulse animation
2. **ConfiguringLanguage**: Language selection dialog (input/output)
3. **ConfiguringMode**: Mode selection (General vs Doctor Scribe)
4. **Recording**: Recording screen with timer and stop button
5. **Processing**: Loading screen during transcription/analysis
6. **ShowingResults**: Beautiful formatted results display

#### Design System
- Custom color palette (ScribePrimary, ScribeAccent, etc.)
- Material3 components with rounded corners
- Smooth animations and transitions
- Responsive layout with vertical scrolling for results

#### Language Support
Pre-configured languages:
- English (US), Spanish, French, German
- Chinese, Japanese, Arabic, Hindi, Portuguese

### Tool Implementations

#### 1. RecordAudioTool
- Uses Android MediaRecorder API
- Saves audio as M4A (AAC encoding, 44.1kHz, 96kbps)
- Stores in app's external files directory
- Permission check for RECORD_AUDIO

#### 2. StopRecordingTool
- Safely stops and releases MediaRecorder
- Returns file path for transcription
- Error handling for failed recordings

#### 3. TranscribeAudioTool
- Uses Gemma model for transcription
- Language-aware transcription
- Returns clean transcribed text

#### 4. ExtractInsightsTool
- Prompts Gemma to analyze transcription
- Extracts: summary, key points, action items
- JSON response parsing with fallback
- Stores result in ScribeSessionStore

#### 5. FormatSoapNoteTool
- Medical-specific transcription formatting
- SOAP structure: Subjective, Objective, Assessment, Plan
- Handles missing sections gracefully
- Stores result in ScribeSessionStore

### Integration Points

#### AgentOrchestrator
- Added Scribe to reactive profiles list
- No planning phase - direct tool execution
- Session state managed through ScribeSessionStore

#### PromptBuilder
- Custom system prompt for Scribe profile
- Tool workflow documentation
- Mode-specific guidance (General vs SOAP)

#### PlannerToolSchema
- `ScribePlannerToolSchema` defines all tools
- Proper parameter descriptions for agent
- Integrated into `geniePlannerToolProviders`

#### ToolRegistry
- All 5 Scribe tools registered
- Available when Scribe profile is active

#### GenieAccessibilityService
- Implements ToolServiceContext methods:
  - `startAudioRecording()`
  - `stopAudioRecording()`
  - `transcribeAudio()`
  - `extractInsights()`
  - `formatSoapNote()`
- ScribeOverlay rendering in debug mode
- Full-screen layout configuration
- Coroutine-based async processing

## User Flow

### Pre-Recording Configuration
1. User taps floating record button
2. **Step 1**: Select input language (what they'll speak)
3. **Step 2**: Select output language (response language)
4. **Step 3**: Choose mode:
   - General: Standard notes with insights
   - Doctor Scribe: Medical SOAP format

### Recording
1. Press record button → recording starts
2. Visual feedback: pulsing red microphone icon
3. Real-time duration timer (MM:SS format)
4. Press stop → recording saved

### Processing
1. Automatic transcription using Gemma model
2. Mode-specific processing:
   - **General**: Extract summary, key points, actions
   - **Doctor Scribe**: Format as SOAP note
3. Loading screen with progress indicator

### Results Display

#### General Mode
Beautiful card-based layout:
- **Summary Card**: Concise 2-3 sentence overview (blue)
- **Key Points Card**: Bulleted insights (orange)
- **Action Items Card**: Checkboxes for tasks (green)
- **Full Transcription**: Complete text (gray)

#### Doctor Scribe Mode
Medical SOAP note sections:
- **Subjective**: Patient complaints (blue)
- **Objective**: Clinical findings (purple)
- **Assessment**: Diagnosis (orange)
- **Plan**: Treatment plan (green)
- **Original Transcription**: Full text

Each section has:
- Color-coded icon badge
- Section title and subtitle
- Divider line
- Formatted content

### Completion
- "Done" button returns to idle state
- Session cleared, ready for next recording

## Technical Details

### Permissions
- `RECORD_AUDIO`: Already declared in AndroidManifest.xml
- Runtime permission check in startAudioRecording()

### Audio Format
- Container: MPEG-4 (.m4a)
- Codec: AAC
- Sample Rate: 44.1 kHz
- Bitrate: 96 kbps
- Source: Device microphone

### File Storage
- Location: `app.getExternalFilesDir(Environment.DIRECTORY_MUSIC)`
- Naming: `scribe_recording_<timestamp>.m4a`
- Automatic cleanup handled by Android

### Error Handling
- Recording failures: Permission denied, storage issues
- Transcription errors: Model failures, invalid audio
- Processing errors: JSON parsing, network issues
- UI feedback: Error view with clear messaging

### Multilingual Support
- Input language: Configurable per recording
- Output language: Independent from input
- Model handles translation if languages differ
- Fallback to English if language unsupported

## Files Created/Modified

### New Files
1. `ScribeModels.kt` - Data models and session store
2. `ScribeRecordingUI.kt` - Complete UI implementation
3. `SCRIBE_FEATURE.md` - This documentation

### Modified Files
1. `ToolProfile.kt` - Added Scribe enum
2. `PlannerToolSchema.kt` - Added ScribePlannerToolSchema
3. `ToolImplementations.kt` - Added 5 Scribe tools
4. `ToolRegistry.kt` - Registered Scribe tools
5. `GenieTool.kt` - Added ToolServiceContext methods
6. `GenieAccessibilityService.kt` - Implemented Scribe methods and UI
7. `AgentOrchestrator.kt` - Added Scribe to reactive profiles
8. `PromptBuilder.kt` - Added scribeSystemPrompt

## Testing Checklist

### Basic Flow
- [ ] Select Scribe profile in MainActivity
- [ ] Enable accessibility service
- [ ] Tap record button appears
- [ ] Language configuration shows
- [ ] Mode selection works
- [ ] Recording starts with visual feedback
- [ ] Timer updates correctly
- [ ] Stop recording works
- [ ] Processing screen appears
- [ ] Results display correctly

### General Mode
- [ ] Summary is concise and accurate
- [ ] Key points extracted properly
- [ ] Action items identified
- [ ] Full transcription visible
- [ ] UI is readable and polished

### Doctor Scribe Mode
- [ ] SOAP sections populated
- [ ] Medical terminology preserved
- [ ] Formatting is clean
- [ ] Sections are distinguishable
- [ ] Original transcription available

### Edge Cases
- [ ] Very short recordings (< 5 seconds)
- [ ] Long recordings (> 5 minutes)
- [ ] Silent recordings
- [ ] Background noise handling
- [ ] Language switching works
- [ ] Multiple recordings in sequence
- [ ] App interruption during recording
- [ ] Storage permission denied

### UI/UX
- [ ] Animations are smooth
- [ ] Colors are consistent
- [ ] Text is legible
- [ ] Buttons are responsive
- [ ] Scrolling works in results
- [ ] Back navigation works
- [ ] Close button accessible
- [ ] Full-screen overlay works

## Future Enhancements

### Potential Features
1. **Export**: Save results as PDF or share via intent
2. **History**: Store past recordings and results
3. **Templates**: Predefined SOAP note templates
4. **Voice Commands**: Start/stop recording via voice
5. **Real-time Transcription**: Live transcription during recording
6. **Speaker Diarization**: Identify different speakers
7. **Custom Fields**: User-defined sections for SOAP notes
8. **Cloud Sync**: Backup recordings to cloud storage
9. **Encryption**: Secure sensitive medical data
10. **Offline Mode**: Process without internet connection

### Performance Optimizations
- Chunk processing for long recordings
- Background service for transcription
- Caching transcription results
- Reduce memory footprint
- Optimize model inference

### Accessibility
- Voice feedback during recording
- Haptic feedback on state changes
- High contrast mode support
- Font size customization
- Screen reader compatibility

## Notes

### Design Decisions
- **Reactive Profile**: No planning phase needed, tools execute sequentially
- **Session Store**: In-memory storage avoids database complexity
- **UI-First**: Configuration before recording ensures user intent is clear
- **Mode Separation**: Clear distinction between General and Medical use cases
- **Full Processing**: All extraction happens in the service, UI just displays

### Known Limitations
- Transcription quality depends on audio clarity
- Model inference time varies by device
- Long recordings may take time to process
- No support for real-time transcription
- Limited to on-device Gemma model capabilities

### Security Considerations
- Audio files stored locally, not transmitted
- No cloud processing of sensitive data
- HIPAA compliance for medical transcriptions (user responsibility)
- Clear data on session complete
- No persistent storage of transcriptions by default
