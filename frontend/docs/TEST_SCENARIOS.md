# Configuration Constructor - Test Scenarios

This document outlines end-to-end test scenarios for the Configuration Constructor feature.

## Prerequisites

- Backend API running at `http://localhost:8080`
- Frontend dev server running at `http://localhost:5173`
- Database with at least 1 channel
- Network connectivity between frontend and backend

---

## Test Suite 1: Initial Load

### TC-1.1: Graph loads successfully with data

**Steps:**
1. Navigate to Constructor tab
2. Wait for loading to complete

**Expected Results:**
- [ ] Loading spinner displays while fetching
- [ ] Summary bar shows channel counts
- [ ] Graph renders with nodes
- [ ] Minimap shows overview
- [ ] Controls are visible (zoom, fit view, fullscreen)

### TC-1.2: Empty state handling

**Steps:**
1. Ensure database has no channels
2. Navigate to Constructor tab

**Expected Results:**
- [ ] Empty state message displays
- [ ] No JavaScript errors in console

### TC-1.3: Error handling on API failure

**Steps:**
1. Stop the backend API
2. Navigate to Constructor tab

**Expected Results:**
- [ ] Error message displays
- [ ] "Try Again" button is visible
- [ ] Clicking "Try Again" attempts reload

---

## Test Suite 2: Graph Navigation

### TC-2.1: Mouse navigation

**Steps:**
1. Load the graph
2. Click and drag on canvas to pan
3. Use mouse wheel to zoom

**Expected Results:**
- [ ] Canvas pans smoothly
- [ ] Zoom in/out works
- [ ] Minimap updates to show current view

### TC-2.2: Keyboard navigation

**Steps:**
1. Load the graph
2. Press Tab key
3. Press Arrow keys
4. Press Home/End keys

**Expected Results:**
- [ ] Tab cycles through nodes
- [ ] Arrow keys navigate spatially
- [ ] Home goes to first node
- [ ] End goes to last node
- [ ] Selected node is visually highlighted

### TC-2.3: Touch navigation (tablet/mobile)

**Steps:**
1. Open on touch device
2. Pinch to zoom
3. Drag to pan
4. Tap node to select

**Expected Results:**
- [ ] Pinch zoom works smoothly
- [ ] Pan gesture works
- [ ] Tap selects node
- [ ] Touch targets are large enough (44px+)

### TC-2.4: Search functionality

**Steps:**
1. Click search icon or press Ctrl+F
2. Type a channel name
3. Click on search result

**Expected Results:**
- [ ] Search panel opens
- [ ] Results filter as you type
- [ ] Clicking result selects node
- [ ] Graph centers on selected node

---

## Test Suite 3: Node Selection

### TC-3.1: Channel node selection

**Steps:**
1. Click on a channel node

**Expected Results:**
- [ ] Side panel opens
- [ ] Channel details display (title, ID, type)
- [ ] Status indicator shows correct status
- [ ] Panel is read-only (no edit buttons)

### TC-3.2: Chat config node selection

**Steps:**
1. Click on a chat config node

**Expected Results:**
- [ ] Side panel opens with form
- [ ] Current values are populated
- [ ] Enable/disable toggle works
- [ ] Save button is present

### TC-3.3: LLM parameters node selection

**Steps:**
1. Click on an LLM parameters node

**Expected Results:**
- [ ] Form shows model, temperature, max tokens
- [ ] Slider/input controls work
- [ ] Save updates backend

### TC-3.4: Deselection

**Steps:**
1. Select a node
2. Click on empty canvas area

**Expected Results:**
- [ ] Side panel closes
- [ ] Node is deselected
- [ ] Screen reader announces "Selection cleared"

---

## Test Suite 4: Configuration Editing

### TC-4.1: Edit chat configuration

**Steps:**
1. Select a chat config node
2. Toggle enabled status
3. Click Save

**Expected Results:**
- [ ] Save button shows loading state
- [ ] Success message displays
- [ ] Node status updates
- [ ] Backend data is updated

### TC-4.2: Edit LLM parameters

**Steps:**
1. Select LLM node
2. Change temperature to 0.5
3. Change model to different value
4. Click Save

**Expected Results:**
- [ ] Form validates input
- [ ] Save persists changes
- [ ] Refresh shows updated values

### TC-4.3: Edit rate limits

**Steps:**
1. Select rate limits node
2. Set messages per hour to 10
3. Click Save
4. Click Reset Counters

**Expected Results:**
- [ ] Limits save successfully
- [ ] Reset button resets counters
- [ ] Success messages display

### TC-4.4: Edit context settings

**Steps:**
1. Select context settings node
2. Change history limit
3. Toggle compression
4. Save

**Expected Results:**
- [ ] Changes save correctly
- [ ] Node reflects new status

### TC-4.5: Validation errors

**Steps:**
1. Select LLM node
2. Enter invalid temperature (e.g., 5)
3. Try to save

**Expected Results:**
- [ ] Validation error displays
- [ ] Save is prevented or warns
- [ ] Error message is descriptive

---

## Test Suite 5: Trigger Management

### TC-5.1: View trigger details

**Steps:**
1. Click on a trigger node

**Expected Results:**
- [ ] Panel shows trigger type
- [ ] Keywords are displayed
- [ ] Active status shown

### TC-5.2: Toggle trigger

**Steps:**
1. Select trigger node
2. Click Toggle Active button

**Expected Results:**
- [ ] Status changes
- [ ] Node color updates
- [ ] Backend updated

### TC-5.3: Delete trigger

**Steps:**
1. Select trigger node
2. Click Delete button
3. Confirm deletion

**Expected Results:**
- [ ] Confirmation dialog appears
- [ ] Trigger is removed
- [ ] Node disappears from graph

---

## Test Suite 6: Template Management

### TC-6.1: View template details

**Steps:**
1. Click on a template node

**Expected Results:**
- [ ] Panel shows template content
- [ ] Style and tone displayed
- [ ] Default indicator shown

### TC-6.2: Set default template

**Steps:**
1. Select non-default template
2. Click Set as Default

**Expected Results:**
- [ ] Template becomes default
- [ ] Previous default loses indicator
- [ ] Backend updated

### TC-6.3: Delete template

**Steps:**
1. Select template
2. Click Delete
3. Confirm

**Expected Results:**
- [ ] Template removed
- [ ] Graph updates

---

## Test Suite 7: Restriction Management

### TC-7.1: View restriction details

**Steps:**
1. Click restriction node

**Expected Results:**
- [ ] Panel shows restriction type
- [ ] Action displayed
- [ ] Keywords/categories shown

### TC-7.2: Toggle restriction

**Steps:**
1. Select restriction
2. Toggle active

**Expected Results:**
- [ ] Status changes
- [ ] Node updates

### TC-7.3: Delete restriction

**Steps:**
1. Select restriction
2. Delete

**Expected Results:**
- [ ] Restriction removed
- [ ] Graph updates

---

## Test Suite 8: Digest Persona Workflow

### TC-8.1: Create digest persona via wizard

**Steps:**
1. Click "+ Create Digest Persona"
2. Fill in Step 1 (Basics): name, style, language
3. Fill in Step 2 (Target): select channel
4. Fill in Step 3 (Schedule): set cron
5. Fill in Step 4 (Content): add keywords
6. Review in Step 5
7. Click Create

**Expected Results:**
- [ ] Wizard opens
- [ ] Navigation between steps works
- [ ] Validation prevents invalid input
- [ ] Create button creates persona
- [ ] New node appears in graph

### TC-8.2: Test digest generation

**Steps:**
1. Select digest persona node
2. Click "Test Generate"
3. Wait for generation

**Expected Results:**
- [ ] Loading state shows
- [ ] Preview of digest displays
- [ ] No errors in console

### TC-8.3: View digest history

**Steps:**
1. Select digest persona
2. Scroll to History section

**Expected Results:**
- [ ] Past digests listed
- [ ] Timestamps shown
- [ ] Status indicators correct

### TC-8.4: Delete digest persona

**Steps:**
1. Select digest persona
2. Click Delete
3. Confirm

**Expected Results:**
- [ ] Confirmation dialog shows
- [ ] Persona removed from database
- [ ] Node removed from graph

---

## Test Suite 9: Bot Persona

### TC-9.1: View bot persona

**Steps:**
1. Click bot persona node

**Expected Results:**
- [ ] Panel shows bot ID
- [ ] Languages listed
- [ ] Preview name shown

### TC-9.2: Edit language settings

**Steps:**
1. Select bot persona
2. Change language selection
3. Save

**Expected Results:**
- [ ] Changes persist
- [ ] Node updates

---

## Test Suite 10: Search Configuration

### TC-10.1: View search config

**Steps:**
1. Click search config node

**Expected Results:**
- [ ] Provider shown
- [ ] Triggers displayed
- [ ] Rate limit shown

### TC-10.2: Edit search config

**Steps:**
1. Enable/disable search
2. Change provider
3. Modify rate limit
4. Save

**Expected Results:**
- [ ] All fields editable
- [ ] Changes save
- [ ] Node status updates

---

## Test Suite 11: Graph Controls

### TC-11.1: Zoom controls

**Steps:**
1. Click zoom in (+)
2. Click zoom out (-)
3. Click fit view

**Expected Results:**
- [ ] Zoom changes smoothly
- [ ] Fit view shows all nodes

### TC-11.2: Fullscreen mode

**Steps:**
1. Click fullscreen button
2. Press Escape or click again

**Expected Results:**
- [ ] Graph goes fullscreen
- [ ] Controls remain accessible
- [ ] Can exit fullscreen

### TC-11.3: Minimap navigation

**Steps:**
1. Click on minimap
2. Drag within minimap

**Expected Results:**
- [ ] Main view pans to clicked location
- [ ] Drag pans view

---

## Test Suite 12: Accessibility

### TC-12.1: Screen reader support

**Steps:**
1. Enable screen reader
2. Navigate to Constructor tab
3. Navigate through nodes

**Expected Results:**
- [ ] Page title announced
- [ ] Nodes are announced with type and label
- [ ] Selection changes announced
- [ ] Instructions are provided

### TC-12.2: Keyboard-only usage

**Steps:**
1. Navigate using only keyboard
2. Try all features without mouse

**Expected Results:**
- [ ] Skip link works
- [ ] All nodes reachable
- [ ] All buttons activatable
- [ ] No focus traps

### TC-12.3: High contrast mode

**Steps:**
1. Enable high contrast in OS
2. View Constructor

**Expected Results:**
- [ ] All elements visible
- [ ] Sufficient contrast
- [ ] Status colors distinguishable

### TC-12.4: Reduced motion

**Steps:**
1. Enable reduced motion in OS
2. View Constructor

**Expected Results:**
- [ ] Animations disabled
- [ ] No flickering
- [ ] Loading states static

---

## Test Suite 13: Performance

### TC-13.1: Large dataset (50+ channels)

**Steps:**
1. Load database with 50+ channels
2. Navigate to Constructor

**Expected Results:**
- [ ] Initial load < 5s
- [ ] Graph renders without lag
- [ ] Pan/zoom smooth at 60fps
- [ ] Memory usage reasonable

### TC-13.2: Rapid interactions

**Steps:**
1. Quickly select/deselect nodes
2. Rapidly zoom in/out
3. Quick consecutive saves

**Expected Results:**
- [ ] No crashes
- [ ] No duplicate saves
- [ ] UI remains responsive

---

## Test Suite 14: Error Recovery

### TC-14.1: Network disconnect during save

**Steps:**
1. Start editing a node
2. Disconnect network
3. Try to save

**Expected Results:**
- [ ] Error message displays
- [ ] Data not lost
- [ ] Can retry when reconnected

### TC-14.2: Concurrent modification

**Steps:**
1. Open Constructor in two tabs
2. Edit same entity in both
3. Save from both

**Expected Results:**
- [ ] One save succeeds
- [ ] Second shows conflict or updates

### TC-14.3: Session expiry

**Steps:**
1. Let session expire
2. Try to save

**Expected Results:**
- [ ] Appropriate error message
- [ ] Redirect to login or re-auth prompt

---

## Test Suite 15: Responsive Design

### TC-15.1: Desktop (1920x1080)

**Expected Results:**
- [ ] Full layout with side panel
- [ ] All controls visible
- [ ] Comfortable spacing

### TC-15.2: Tablet (1024x768)

**Expected Results:**
- [ ] Layout adapts
- [ ] Side panel usable
- [ ] Touch-friendly

### TC-15.3: Mobile (375x667)

**Expected Results:**
- [ ] Layout stacks appropriately
- [ ] Panel doesn't overflow
- [ ] All features accessible

---

## Bug Report Template

When filing bugs found during testing, use this template:

```
**Test Case:** TC-X.X
**Severity:** Critical / High / Medium / Low
**Browser:** Chrome 120 / Firefox 121 / Safari 17 / Edge 120
**Device:** Desktop / Tablet / Mobile
**OS:** Windows 11 / macOS Sonoma / iOS 17 / Android 14

**Steps to Reproduce:**
1. Step 1
2. Step 2
3. ...

**Expected Result:**
What should happen

**Actual Result:**
What actually happened

**Screenshots:**
[Attach screenshots]

**Console Logs:**
[Attach relevant console output]
```

---

## Sign-off Checklist

Before release, verify:

- [ ] All Critical test cases pass
- [ ] All High priority test cases pass
- [ ] No regressions from previous version
- [ ] Performance benchmarks met
- [ ] Accessibility audit complete
- [ ] Cross-browser testing complete
- [ ] Mobile testing complete

**Tested By:** _________________
**Date:** _________________
**Build Version:** _________________
