# BaerenEd - Educational Android App

BaerenEd is a native Android educational app designed for children, featuring interactive games and activities that help kids learn while earning reward time for approved apps like Pokemon Go.

## Features

- **Native Android TTS**: Uses Android's built-in text-to-speech without web dependencies
- **Dynamic Content**: Games and content updated via JSON files from GitHub
- **Multiple Game Types**: Quiz, Memory, Word pronunciation, and Math games
- **Reward System Integration**: Earns reward time that integrates with BaerenLock launcher
- **Offline Support**: Content cached locally for offline use
- **Parental Controls**: Games can require reward time earned through educational activities

## Architecture

### Main Components

1. **MainActivity**: Displays game grid, loads content from JSON
2. **GameActivity**: Handles all game types (quiz, memory, word, math)
3. **ContentUpdateService**: Downloads and caches content from GitHub
4. **RewardSelectionActivity**: Allows selection of reward apps after earning time

### Content Structure

#### Main Content (`main_content.json`)
```json
{
  "games": [
    {
      "id": "animals_quiz",
      "title": "Animal Quiz",
      "description": "Learn about different animals",
      "type": "quiz",
      "requiresRewardTime": false,
      "difficulty": "easy",
      "estimatedTime": 5
    }
  ],
  "version": "1.0.0"
}
```

#### Game Content (`games/{game_id}.json`)
```json
{
  "questions": [
    {
      "question": "What sound does a cow make?",
      "options": ["Moo", "Meow", "Woof", "Oink"],
      "correctAnswer": "Moo",
      "explanation": "Cows say 'Moo'!"
    }
  ],
  "memoryCards": [
    {
      "id": "apple_1",
      "symbol": "🍎",
      "pairId": "apple_pair"
    }
  ]
}
```

## Setup Instructions

### 1. GitHub Repository Setup
Create a GitHub repository and add JSON files:
- `main_content.json` - Main game list
- `games/` directory with individual game JSON files

### 2. Content URLs
Update the URLs in `ContentUpdateService.kt` to point to your GitHub repository:
```kotlin
"https://raw.githubusercontent.com/YOUR_USERNAME/BaerenEd/main/main_content.json"
"https://raw.githubusercontent.com/YOUR_USERNAME/BaerenEd/main/games/${gameId}.json"
```

### 3. Build and Install
```bash
# Build the app
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Game Types

### Quiz Games
- Multiple choice questions with TTS feedback
- Immediate answer checking
- Educational explanations

### Memory Games
- Card matching with visual symbols
- TTS pronunciation of symbols
- Pair matching gameplay

### Word Games
- Word pronunciation practice
- TTS audio feedback
- Vocabulary building

### Math Games
- Basic arithmetic problems
- Multiple choice answers
- Progressive difficulty

## Reward System Integration

1. **Earn Reward Time**: Complete educational games to earn minutes
2. **Reward Time Storage**: Time stored in shared preferences
3. **BaerenLock Integration**: Time can be used to unlock apps in BaerenLock
4. **Time Expiration**: Reward time expires after 24 hours

## TTS Integration

The app uses Android's native TTS engine:
- Language set to US English by default
- Queue flush for immediate feedback
- Error handling for unsupported languages

## Content Management

### Automatic Updates
- Content checked for updates on app start
- Background service downloads new content
- Version comparison to detect updates

### Offline Support
- Content cached in app's internal storage
- Works without internet connection
- Automatic cache management

### Content Validation
- JSON parsing with error handling
- Fallback to cached content on network errors
- Version checking for content updates

## Customization

### Adding New Games
1. Create game JSON file in `games/` directory
2. Add game entry to `main_content.json`
3. Update game type handling in `GameActivity.kt`

### Modifying Game Logic
- Quiz: `initializeQuizGame()`, `checkAnswer()`
- Memory: `initializeMemoryGame()`, `flipCard()`
- Word: `initializeWordGame()`, `speakWord()`
- Math: `initializeMathGame()`, `checkMathAnswer()`

## Dependencies

- AndroidX Core & Lifecycle
- OkHttp for network requests
- Gson for JSON parsing
- Android TTS

## Development Notes

- Uses Kotlin coroutines for async operations
- Follows Android architecture guidelines
- Comprehensive error handling
- Logging for debugging

## Integration with BaerenLock

BaerenEd integrates with the BaerenLock launcher:
1. Reward time earned in BaerenEd
2. Time used to unlock apps in BaerenLock
3. Shared preferences for reward data
4. Launcher takes control when reward time expires

This creates a complete educational ecosystem where kids learn through games and earn supervised playtime on approved apps.

