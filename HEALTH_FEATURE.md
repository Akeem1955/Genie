# Health Profile Feature Implementation

## Overview
The **Health Profile** is a UI-driven tool profile for Genie that provides two main features:
1. **Food Calorie & Nutrient Analyzer**: Analyze food images for nutritional information
2. **Health Topics Library**: Search and browse WHO health fact sheets for 200+ topics

## Architecture

### Profile System Integration
- Added `Health` enum to `ToolProfile.kt` with no agent tools (`toolNames = emptySet()`)
- Configured as a reactive profile (no planning phase)
- Full-screen overlay UI similar to Scribe profile
- Completely UI-driven with direct service method calls

### Data Models (`HealthModels.kt`)

#### Food Analysis Models
- `NutrientInfo`: Individual nutrient with amount, unit, explanation, and daily value %
- `FoodNutritionAnalysis`: Complete nutritional breakdown
  - Food name, calories, serving size
  - Macronutrients, vitamins, minerals, other nutrients
  - Nutrition coverage analysis
- `NutritionCoverage`: What's well-covered vs missing nutrients

#### Health Topics Models
- `HealthRecord`: Single disease/topic entry from WHO database
  - Disease name, source URL
  - Data map with flexible value types (text, lists, nested maps)
- `DataValue`: Sealed class for different JSON value types
  - `Text`: Simple string content
  - `ListText`: Bullet point lists (e.g., "Key facts")
  - `NestedMap`: Nested key-value pairs
- `HealthTopicIndex`: Lightweight index of all 200+ topic names
- `HealthSessionStore`: In-memory session state management

### Health Library Manager (`HealthLibraryManager.kt`)

#### JSON Database Management
The WHO health topics JSON file (`local_health_library.json`) contains ~150k tokens - too large to load into model context. The `HealthLibraryManager` treats it as a queryable database:

**Key Methods:**
- `loadTopicIndex()`: Extracts only disease names (small, ~500-1000 tokens)
  - Cached after first load
  - Sorted alphabetically
  - Can be shared with model for topic matching

- `queryTopic(diseaseName)`: Loads single disease entry
  - Only parses the specific entry (1-3k tokens)
  - Returns `HealthRecord` with all sections
  - Fast lookup, minimal memory usage

- `searchTopics(query)`: Fuzzy search across topic names
  - Case-insensitive substring matching
  - Returns top 10 matches

- `getTopicListForModel()`: Formatted topic list for model context
  - Grouped by first letter for readability
  - Used when model needs to match user queries to topics

#### Topic Matching Flow
```
User query: "Tell me about diabetes"
    ↓
Model sees: [List of 200+ topic names]
    ↓
Model identifies: "Diabetes"
    ↓
System loads: Only Diabetes entry from JSON
    ↓
UI displays: Full Diabetes information
```

### UI Components (`HealthUI.kt`)

#### UI States
1. **Idle**: Floating action button with pulse animation
2. **SelectingFeature**: Choose between Food Analyzer or Health Topics
3. **SelectingImageSource**: Screen capture vs gallery picker
4. **Processing**: Loading indicator with status message
5. **ShowingFoodAnalysis**: Beautiful nutrition breakdown display
6. **ShowingHealthTopic**: Formatted WHO fact sheet display
7. **SearchingTopic**: Topic search interface with popular topics
8. **Error**: Error message display

#### Design System
- Custom color palette (HealthPrimary green, HealthAccent blue, etc.)
- Color-coded nutrient sections:
  - **Purple**: Macronutrients (protein, carbs, fats)
  - **Cyan**: Vitamins
  - **Pink**: Minerals
  - **Orange**: Other nutrients
- Material3 components with rounded corners
- Smooth animations and transitions
- Responsive scrolling for long content
- Self-explanatory visual indicators

#### Food Analysis Display
Beautiful card-based layout:
- **Header**: Food name, total calories, serving size
- **Nutrient Sections**: Grouped by category with color coding
  - Each nutrient shows: name, amount, daily value %, explanation
  - Progress bars for daily value visualization
- **Coverage Analysis Card**:
  - "Well Covered" nutrients (green checkmarks)
  - "Missing/Insufficient" nutrients (orange warnings)
  - Summary text explaining nutritional value

#### Health Topics Display
Clean fact sheet layout:
- **Header**: Disease name, WHO source attribution
- **Dynamic Sections**: Each data field as a card
  - Text sections: Paragraphs with proper spacing
  - List sections: Bullet points with colored indicators
  - Nested maps: Key-value pairs with bold keys
- Color-coded section titles (blue accent)
- Easy scrolling through long articles

#### Topic Search Interface
- Text input field for custom queries
- "Search" button (enabled when query is non-empty)
- **Popular Topics**: 8 quick-access chips
  - Diabetes, Cancer, Malaria, COVID-19
  - Hypertension, Asthma, Tuberculosis, HIV and AIDS
- Grid layout for topic chips

## Integration Points

### AgentOrchestrator
- Added `Health` to `isReactiveProfile()` list
- No planning phase - UI controls entire workflow
- Session state managed through `HealthSessionStore`

### PromptBuilder
- Minimal system prompt for Health profile
- No tool definitions needed (UI-driven)

### PlannerToolSchema
- Returns `emptyList()` for Health profile
- No planner tools registered

### ToolRegistry
- No Health tools registered (UI handles everything)

### GenieAccessibilityService
- Implements Health service methods:
  - `analyzeFoodImage(bitmap)`: Sends image to Gemma for nutrition analysis
  - `searchHealthTopic(query)`: Matches query to topic and loads data
  - `loadBitmapFromUri(uri)`: Loads image from gallery picker
- HealthOverlay rendering in full-screen mode
- Lazy-initialized `HealthLibraryManager`
- Coroutine-based async processing in `serviceScope`

## User Flow

### Feature 1: Food Calorie Analyzer

#### Step 1: Launch
1. User taps floating Health button (green medical icon)
2. Feature selection screen appears

#### Step 2: Select Food Analyzer
1. User taps "Food Analyzer" card
2. Image source selection appears

#### Step 3: Choose Image Source
**Option A: Screen Capture**
- User taps "Capture Screen"
- Current screen is captured as image
- Processing begins

**Option B: Gallery Picker**
- User taps "Pick from Gallery"
- Android file picker opens
- User selects existing food image
- Processing begins

#### Step 4: Processing
- "Analyzing food image..." loading screen
- Image sent to Gemma model with nutrition analysis prompt
- Model identifies food and extracts:
  - Calories, serving size
  - All macronutrients with explanations
  - Vitamins and minerals present
  - Daily value percentages
  - Coverage analysis

#### Step 5: Results Display
Beautiful multi-card layout:
- Total calories prominently displayed
- Each nutrient category in separate card:
  - **Macronutrients** (purple accent)
  - **Vitamins** (cyan accent)
  - **Minerals** (pink accent)
  - **Other Nutrients** (orange accent)
- Each nutrient shows:
  - Name and amount (e.g., "Protein: 25g")
  - Daily value progress bar
  - Explanation (e.g., "Builds and repairs tissues")
- **Coverage card** at bottom:
  - Green checks for well-covered nutrients
  - Orange warnings for missing nutrients
  - Summary paragraph
- "Done" button returns to idle

### Feature 2: Health Topics Library

#### Step 1: Launch
1. User taps floating Health button
2. Feature selection screen appears

#### Step 2: Select Health Topics
1. User taps "Health Topics" card
2. Search interface appears

#### Step 3: Search Topic
**Option A: Manual Search**
- User types query (e.g., "heart disease", "flu symptoms")
- Taps "Search" button
- Processing begins

**Option B: Popular Topics**
- User taps one of 8 popular topic chips
- Processing begins immediately

#### Step 4: Topic Matching
- "Loading health topic..." indicator
- Query sent to model with full topic index
- Model identifies best matching topic name
- System queries that specific entry from JSON
- Only that entry's data loaded (1-3k tokens)

#### Step 5: Results Display
Clean fact sheet layout:
- Disease name as header
- "Source: WHO" attribution
- Multiple expandable sections:
  - **Key Facts**: Bullet points with quick facts
  - **Overview**: Detailed description
  - **Scope of the Problem**: Statistics and prevalence
  - **Consequences**: Health impacts
  - **Other sections**: Dynamic based on topic
- Each section as separate card with blue accent
- Smooth scrolling through long content
- "Done" button returns to idle

## Technical Details

### Food Image Analysis

#### Prompt Engineering
The food analysis prompt asks Gemma to:
1. Identify the food item
2. Estimate serving size
3. Calculate total calories
4. List all macronutrients with amounts and daily values
5. Identify vitamins and minerals present
6. Explain what each nutrient does in the body
7. Analyze what's covered vs missing
8. Provide summary of nutritional value

#### JSON Response Parsing
- Model returns structured JSON
- Markdown code blocks stripped (```json ... ```)
- Parsed into `FoodNutritionAnalysis` data class
- Fallback error handling for malformed responses

#### Image Handling
- Bitmaps converted to PNG bytes
- Sent as `imagePngBytes` parameter to Gemma
- High quality (100% compression)

### Health Topic Search

#### Two-Stage Lookup
**Stage 1: Topic Identification**
- Model sees list of all 200+ topic names
- Identifies best match from user query
- Returns single topic name

**Stage 2: Data Retrieval**
- System queries JSON for that specific entry
- Only that entry parsed (not entire 150k token file)
- `HealthRecord` returned with all sections

#### Why This Approach?
- **Memory efficient**: Never loads entire JSON
- **Fast**: Only parses what's needed
- **Token efficient**: Model only processes topic names, not full content
- **Scalable**: Can handle thousands of topics

### Error Handling
- Image capture failures: Permission issues, bitmap null
- Image loading failures: Invalid URI, corrupted file
- Analysis errors: Model failures, JSON parsing errors
- Topic not found: Fuzzy matching suggests alternatives
- UI feedback: Clear error messages with dismiss button

### Permissions
- No special permissions required for Health profile
- Uses existing accessibility service permissions
- Gallery picker uses Android system file picker (no STORAGE permission needed on modern Android)

## Files Created/Modified

### New Files
1. `HealthModels.kt` - All data models and session store
2. `HealthLibraryManager.kt` - JSON database manager
3. `HealthUI.kt` - Complete UI implementation (~800 lines)
4. `HEALTH_FEATURE.md` - This documentation

### Modified Files
1. `ToolProfile.kt` - Added Health enum with empty tools
2. `AgentOrchestrator.kt` - Added Health to reactive profiles
3. `PlannerToolSchema.kt` - Return empty list for Health
4. `PromptBuilder.kt` - Added minimal Health prompt
5. `GenieAccessibilityService.kt` - Added Health overlay and service methods

## Comparison to Other Profiles

| Aspect | Agentic (Chat) | Orchestrator (Document) | UI-Driven (Health) |
|--------|---------------|------------------------|-------------------|
| **Planning** | Full agent loop | Reactive, no planning | No agent involvement |
| **Tools** | Registered in ToolRegistry | Registered but intercepted | No tools |
| **Decision Making** | Agent decides actions | Orchestrator manages flow | UI controls flow |
| **Model Role** | Plan + Execute + Respond | Execute + Format results | Process images/text only |
| **User Control** | Natural language commands | Natural language + orchestration | Button clicks and selections |
| **Workflow** | Dynamic, multi-step | Hybrid (agent + orchestrator) | Fixed, deterministic |

## Benefits of UI-Driven Architecture

### For Health Profile
1. **Predictability**: Fixed workflow, no surprises
2. **Performance**: No planning overhead, direct method calls
3. **User Control**: Explicit control over image selection and topic search
4. **Visual Feedback**: Clear progress indicators at each stage
5. **Simplicity**: Easier to debug, no agent decisions to trace
6. **Reliability**: Deterministic behavior, no hallucination risk
7. **Token Efficiency**: Model only processes necessary data (images, topic names)

### When to Use UI-Driven
- Workflow is linear and predictable
- User needs explicit control over inputs
- Processing is compute-heavy (image analysis, large database)
- Immediate visual feedback is important
- No complex decision-making required

## Future Enhancements

### Potential Features
1. **Meal Planning**: Combine multiple food analyses into daily meal plan
2. **Nutrition Goals**: Set and track daily nutrient targets
3. **Health History**: Store past food analyses and topic searches
4. **Export**: Share nutrition reports as PDF or image
5. **Barcode Scanner**: Scan food product barcodes for instant nutrition info
6. **Recipe Analysis**: Analyze entire recipes with multiple ingredients
7. **Health Reminders**: Set medication or checkup reminders based on topics
8. **Offline Mode**: Cached topic data for offline access
9. **Multi-language**: Support for non-English health topics
10. **Voice Search**: "Tell me about..." voice commands for topics

### Performance Optimizations
- Cache recent food analyses
- Preload popular health topics
- Compress images before analysis
- Background topic index loading
- Pagination for very long health articles

### Accessibility
- Screen reader support for all UI elements
- Voice feedback during processing
- High contrast mode for readability
- Font size customization
- Keyboard navigation support

## Known Limitations

### Food Analysis
- Accuracy depends on image quality and clarity
- Estimates for serving size and nutrients (not lab-precise)
- Model inference time varies by device (3-10 seconds)
- Cannot analyze ingredients lists from packaging (would need OCR)

### Health Topics
- Limited to WHO fact sheets (200+ topics)
- No real-time health news or updates
- English only (source data limitation)
- Search requires exact or close topic name match
- No cross-referencing between related topics

## Testing Checklist

### Basic Flow
- [ ] Health profile appears in MainActivity profile selector
- [ ] Enable accessibility service with Health profile
- [ ] Floating Health button appears (green medical icon)
- [ ] Feature selection shows both options
- [ ] Close button works at all stages

### Food Analyzer
- [ ] Screen capture works and analyzes correctly
- [ ] Gallery picker opens and accepts images
- [ ] Processing indicator appears during analysis
- [ ] Results display with all nutrient sections
- [ ] Daily value progress bars render correctly
- [ ] Coverage analysis shows covered vs missing
- [ ] Done button returns to idle
- [ ] Error handling for failed captures/analysis

### Health Topics
- [ ] Search input accepts text
- [ ] Popular topic chips work
- [ ] Topic matching finds correct entries
- [ ] Results display all data sections properly
- [ ] Lists render as bullet points
- [ ] Nested data displays correctly
- [ ] Scrolling works for long articles
- [ ] Error handling for topics not found

### UI/UX
- [ ] Colors are consistent and readable
- [ ] Animations are smooth
- [ ] Cards have proper spacing
- [ ] Text is legible on all sections
- [ ] Full-screen overlay covers entire screen
- [ ] System bars padding works correctly
- [ ] Back navigation works intuitively

### Edge Cases
- [ ] Very small food images
- [ ] Very large food images (>10MB)
- [ ] Blurry or unclear food photos
- [ ] Non-food images (error handling)
- [ ] Ambiguous health topic queries
- [ ] Topics with very long articles (scrolling)
- [ ] Missing sections in health data
- [ ] Interrupted processing (app switch)

## Security & Privacy

### Data Handling
- All processing happens on-device (no cloud)
- Food images not stored persistently
- Health searches not logged externally
- Session state cleared on completion
- No personal health data collected

### Compliance
- HIPAA: No PHI storage or transmission
- GDPR: No personal data processing
- Accessibility: Follows Android guidelines
- WHO Attribution: Source clearly displayed

## Notes

### Design Decisions
- **UI-Driven Architecture**: Best fit for fixed workflows with explicit user control
- **JSON as Database**: Efficient token usage, fast lookups
- **Two-Stage Topic Search**: Leverages model for matching, system for retrieval
- **Color-Coded Nutrients**: Visual grouping improves comprehension
- **Full-Screen Overlay**: Immersive experience for complex information display

### Architecture Rationale
Health profile demonstrates that not every feature needs agent autonomy. When:
- Workflow is deterministic
- User wants explicit control
- AI is used for processing (not decision-making)
- Visual presentation is critical

Then a UI-driven architecture is superior to agentic approaches.

### Extensibility
The HealthLibraryManager pattern can be extended to other large knowledge bases:
- Drug information databases
- Medical procedures library
- Symptom checker databases
- First aid guides

The key insight: treat large JSON files as queryable databases, load only what's needed.
