# Configuration Constructor User Guide

## Overview

The Configuration Constructor is an interactive visual interface for managing Telegram bot configurations. It presents your entire configuration landscape as a connected graph, showing channels, their settings, and the relationships between different configuration entities.

## Getting Started

### Accessing the Constructor

1. Navigate to the **Конструктор** (Constructor) tab in the main navigation
2. The system will automatically load all your channel configurations
3. A visual graph will display showing all your configured entities

### Understanding the Interface

The interface consists of three main areas:

1. **Summary Bar** - Shows quick statistics (total channels, configured, enabled, digest personas)
2. **Graph Area** - The main visual representation of your configurations
3. **Detail Panel** - Appears on the right when you select a node

## Visual Elements

### Node Types

The graph displays different entity types as distinct nodes:

| Node Type | Description | Color Coding |
|-----------|-------------|--------------|
| **Channel** | Telegram channels/chats | Status-based colors |
| **Chat Config** | Main configuration for a channel | Green/Yellow/Red |
| **LLM Parameters** | AI model settings | Blue accent |
| **Rate Limits** | Message frequency limits | Orange accent |
| **Context Settings** | Message history settings | Purple accent |
| **Search Config** | Web search integration | Cyan accent |
| **Trigger** | Response trigger conditions | Teal accent |
| **Template** | Response templates | Pink accent |
| **Restriction** | Topic restrictions | Red accent |
| **Digest Persona** | News digest configurations | Indigo accent |
| **Bot Persona** | Bot personality bundles | Green accent |

### Status Indicators

Each node displays its configuration status:

- ✅ **Configured** (Green) - Fully configured and active
- ⚠️ **Partial** (Yellow/Amber) - Missing some required fields
- ❌ **Unconfigured** (Red) - Needs configuration
- ⏸️ **Saved** (Gray) - Configured but not activated
- 🔄 **Loading** (Blue) - Loading configuration data

### Edge Types

Lines connecting nodes indicate relationships:

- **Solid Blue Lines** - Parent-child containment (e.g., Channel → ChatConfig)
- **Dashed Green Lines** - Required dependencies (animated flow)
- **Dashed Gray Lines** - Optional relationships

## Navigation

### Mouse/Touch Controls

- **Click node** - Select and view details in side panel
- **Click empty area** - Deselect current node
- **Drag canvas** - Pan the view
- **Scroll wheel** - Zoom in/out
- **Pinch gesture** (touch) - Zoom in/out

### Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `Tab` | Cycle through nodes (forward) |
| `Shift+Tab` | Cycle through nodes (backward) |
| `Arrow Keys` | Navigate to nearest node in direction |
| `Home` | Jump to first node |
| `End` | Jump to last node |
| `Enter` / `Space` | Select focused node |
| `Escape` | Deselect / Close panel |
| `Ctrl+F` | Open search panel |
| `Ctrl+0` | Fit all nodes in view |
| `F11` | Toggle fullscreen mode |
| `?` | Show keyboard shortcuts help |

### Graph Controls

Located in the top-right corner:

- **Zoom +/-** - Adjust zoom level
- **Fit View** - Auto-fit all nodes
- **Fullscreen** - Toggle fullscreen mode
- **Search** - Open node search panel

### Minimap

The minimap in the bottom-right corner shows an overview of the entire graph:
- Node colors reflect their status
- Click and drag to navigate
- Scroll to zoom

## Configuring Entities

### Editing a Configuration

1. **Click on a node** to select it
2. The **detail panel** opens on the right
3. Make your changes in the form fields
4. Click **Save** to apply changes
5. A success message confirms the update

### Channel Configuration

Channel nodes are read-only and show:
- Channel title and ID
- Channel type (group, channel, etc.)
- Member count
- Current status

### Chat Configuration

Controls main behavior settings:

| Field | Description |
|-------|-------------|
| **Enabled** | Whether the bot is active in this chat |
| **Sync Enabled** | Enable message synchronization |
| **System Prompt** | Base AI personality prompt |

### LLM Parameters

Controls AI response generation:

| Field | Description | Default |
|-------|-------------|---------|
| **Model** | AI model to use | deepseek-chat |
| **Temperature** | Response creativity (0-2) | 0.7 |
| **Max Tokens** | Maximum response length | 1000 |
| **Top P** | Nucleus sampling | 0.9 |
| **Frequency Penalty** | Reduce repetition | 0.0 |
| **Presence Penalty** | Encourage new topics | 0.0 |

### Rate Limits

Controls response frequency:

| Field | Description |
|-------|-------------|
| **Messages Per Minute** | Max responses per minute |
| **Messages Per Hour** | Max responses per hour |
| **Messages Per Day** | Max responses per day |
| **Reset Counters** | Button to reset current counts |

### Context Settings

Controls conversation memory:

| Field | Description |
|-------|-------------|
| **History Limit** | Max messages to remember |
| **Enable Compression** | Compress older messages |
| **Include System Messages** | Include system events |

### Search Configuration

Controls web search integration:

| Field | Description |
|-------|-------------|
| **Provider** | Search engine (google, bing, etc.) |
| **Enable on Triggers** | Keywords that trigger search |
| **Max Results** | Number of results to include |
| **Rate Limit** | Searches per hour |

### Triggers

Define when the bot should respond:

| Field | Description |
|-------|-------------|
| **Type** | Trigger type (keyword, mention, reply, etc.) |
| **Keywords** | Words that trigger response |
| **Pattern** | Regex pattern for matching |
| **Active** | Whether trigger is enabled |

Actions:
- **Toggle** - Enable/disable the trigger
- **Delete** - Remove the trigger

### Templates

Pre-defined response formats:

| Field | Description |
|-------|-------------|
| **Name** | Template identifier |
| **Style** | Writing style (formal, casual, etc.) |
| **Tone** | Emotional tone |
| **Content** | Template text with placeholders |
| **Default** | Set as default template |

Actions:
- **Set Default** - Use as primary template
- **Delete** - Remove the template

### Restrictions

Topic and content restrictions:

| Field | Description |
|-------|-------------|
| **Type** | Restriction type (topic, pattern, etc.) |
| **Action** | What to do (warn, block, etc.) |
| **Keywords** | Restricted words |
| **Categories** | Restricted categories |
| **Active** | Whether restriction is enabled |

## Creating Digest Personas

Digest personas generate automated news summaries.

### Using the Creation Wizard

1. Click **"+ Create Digest Persona"** button
2. Follow the 5-step wizard:

**Step 1: Basics**
- Enter persona name
- Select persona style (Professional, Ironic, Breaking News, Technical, Custom)
- Choose language (EN/RU)

**Step 2: Target**
- Select target channel for posting
- Optionally select bot account

**Step 3: Schedule**
- Set cron expression for timing (e.g., `0 9 * * *` for daily at 9 AM)
- Configure timezone
- Set active hours

**Step 4: Content**
- Add topic keywords for filtering
- Set minimum trust score threshold
- Configure message limits

**Step 5: Review**
- Review all settings
- Click **Create** to finish

### Managing Digest Personas

After creation, select a digest persona node to:

- **Test Generate** - Preview a digest without publishing
- **View History** - See past generated digests
- **Edit Schedule** - Modify timing
- **Delete** - Remove the persona

## Validation

The system validates configurations in real-time:

### Validation Indicators

- **Yellow warning icon** - Non-critical issues (e.g., missing optional settings)
- **Red error icon** - Critical issues (e.g., missing required fields)

### Common Validation Issues

| Issue | Solution |
|-------|----------|
| No triggers configured | Add at least one trigger |
| Invalid cron expression | Use valid cron syntax |
| Temperature out of range | Use value between 0 and 2 |
| Missing target channel | Select a target channel |

### Server-Side Validation

For complex validation, the system contacts the backend:
- Checks for database conflicts
- Validates channel permissions
- Verifies bot account access

## Troubleshooting

### Graph Not Loading

1. Check your network connection
2. Click **Refresh** button
3. Check browser console for errors
4. Verify backend API is running

### Changes Not Saving

1. Check for validation errors in the panel
2. Ensure all required fields are filled
3. Try refreshing and re-applying changes
4. Check network tab for API errors

### Performance Issues

If the graph feels slow:
1. Reduce browser zoom level
2. Close unused browser tabs
3. Try fullscreen mode
4. Graph virtualization handles large datasets automatically

### Keyboard Navigation Not Working

1. Click on the graph area first to focus it
2. Ensure no input field is focused
3. Check that browser shortcuts aren't overriding

## Accessibility

The Configuration Constructor supports:

- **Keyboard Navigation** - Full keyboard control
- **Screen Readers** - ARIA labels and live regions
- **High Contrast** - Respects system preferences
- **Reduced Motion** - Honors prefers-reduced-motion setting
- **Focus Indicators** - Visible focus states

### Skip Link

Press `Tab` when the page loads to reveal the "Skip to graph" link for keyboard users.

## Tips and Best Practices

### Organization

1. **Group related channels** - Similar channels appear near each other
2. **Configure essential settings first** - Enable, triggers, then templates
3. **Use templates** - Consistent responses across channels

### Monitoring

1. **Check status colors regularly** - Yellow/red nodes need attention
2. **Review digest history** - Ensure digests are generating correctly
3. **Monitor rate limits** - Reset if bots hit limits

### Performance

1. **Disable unused channels** - Reduce processing load
2. **Set appropriate rate limits** - Prevent spam detection
3. **Use compression** - For long conversations

## Version History

- **v1.0** - Initial release with full graph visualization
- **v1.1** - Added digest creation wizard
- **v1.2** - Added server-side validation
- **v1.3** - Performance optimizations and accessibility improvements
