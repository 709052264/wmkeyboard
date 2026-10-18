-- Todo List: storage, due dates and reminders.
--
-- Shows both capabilities a plugin can ask for, and the date widget.
--
-- `wm.storage` is a small string-to-string map that belongs to this plugin
-- alone, lives on this device only, and is deleted when the plugin is
-- uninstalled. `wm.reminders` asks the app to show a notification at a time
-- you pick. The app shows it, not this script: nothing here runs in the
-- background, and the notification always carries the plugin's name. Both are
-- declared in plugin.json, so the user sees them before installing.
--
-- Note what is *not* here: no network, no reading your messages. This list
-- cannot leave the phone, because there is nothing in the sandbox to send it
-- with.

local items = {}       -- { id, text, done, due = seconds or nil, remind }
local next_id = 1
local draft = ""
local draft_due = nil  -- the due date for the next item, if any
local draft_remind = true
local selected = nil   -- the id of the item the Details page shows
local notice = nil     -- one line about the last thing that went wrong

-- ---------------------------------------------------------------- storage ---

--- Reads the saved list. Stored as JSON in one key, which is simpler than
--- juggling a key per item and stays well inside the quota. Items saved by
--- the first version have no id, so they get one here.
local function load()
  local raw = wm.storage.get("items")
  if not raw then return end
  local decoded = wm.json.decode(raw)
  if type(decoded) ~= "table" then return end
  items = decoded
  for _, item in ipairs(items) do
    if type(item.id) ~= "number" then item.id = 0 end
    if item.id >= next_id then next_id = item.id + 1 end
  end
  for _, item in ipairs(items) do
    if item.id == 0 then
      item.id = next_id
      next_id = next_id + 1
    end
  end
end

local function save()
  local ok, err = wm.storage.set("items", wm.json.encode(items))
  if not ok then
    -- Over quota, most likely. Say so rather than silently losing an item.
    notice = "Could not save: " .. tostring(err)
    wm.log("could not save: " .. tostring(err))
  end
end

-- Runs once, when the plugin is opened.
load()

-- -------------------------------------------------------------- reminders ---

--- One reminder per item, named after it, so setting it again replaces it.
local function reminder_id(item)
  return "item:" .. item.id
end

--- Makes the app's reminder for an item match the item: set while it is due
--- later, not done, and asked for; cancelled otherwise. wm.reminders is nil
--- when the manifest does not declare the permission, and the list still
--- works without it.
local function sync(item)
  if not wm.reminders then return end
  if item.remind and item.due and not item.done and item.due > os.time() then
    local ok, err = wm.reminders.set(reminder_id(item), item.due, item.text)
    if not ok then notice = "No reminder: " .. tostring(err) end
  else
    wm.reminders.cancel(reminder_id(item))
  end
end

-- ------------------------------------------------------------------ dates ---

local function day_start(t)
  local d = os.date("*t", t)
  return os.time { year = d.year, month = d.month, day = d.day, hour = 0 }
end

--- "today 09:00", "tomorrow 18:30", or "Fri 24 Oct 09:00".
local function when(due)
  local today = day_start(os.time())
  local d = os.date("*t", today)
  local tomorrow = os.time { year = d.year, month = d.month, day = d.day + 1, hour = 0 }
  local day = day_start(due)
  local clock = os.date("%H:%M", due)
  if day == today then return "today " .. clock end
  if day == tomorrow then return "tomorrow " .. clock end
  return os.date("%a %d %b", due) .. " " .. clock
end

local function overdue(item)
  return item.due ~= nil and not item.done and item.due < os.time()
end

--- The line under an item's text, or nil when it has no due date.
local function status(item)
  if not item.due then return nil end
  local line = (overdue(item) and "overdue, was due " or "due ") .. when(item.due)
  -- Only while the app will actually show one: not for a time already past.
  if item.remind and not item.done and item.due > os.time() and wm.reminders then
    line = line .. " · reminder"
  end
  return line
end

-- ------------------------------------------------------------------ order ---

--- Open items first, soonest due first and undated last; finished items
--- after them. The saved list keeps the order things were added in.
local function ordered()
  local out = {}
  for _, item in ipairs(items) do out[#out + 1] = item end
  table.sort(out, function(a, b)
    if a.done ~= b.done then return not a.done end
    if (a.due ~= nil) ~= (b.due ~= nil) then return a.due ~= nil end
    if a.due and b.due and a.due ~= b.due then return a.due < b.due end
    return a.id < b.id
  end)
  return out
end

local function find(id)
  for index, item in ipairs(items) do
    if item.id == id then return item, index end
  end
end

--- The item the Details page shows: the one picked, or the first in order.
local function current()
  local item = selected and find(selected)
  if item then return item end
  return ordered()[1]
end

--- Moves the Details page by one item, round the end.
local function step(by)
  local list = ordered()
  if #list == 0 then return end
  local at = 1
  local shown = current()
  for index, item in ipairs(list) do
    if item == shown then at = index end
  end
  at = (at - 1 + by) % #list + 1
  selected = list[at].id
end

-- ----------------------------------------------------------------- events ---

local function set_done(item, done)
  item.done = done
  sync(item)
  save()
end

local function remove(item)
  local _, index = find(item.id)
  if not index then return end
  if wm.reminders then wm.reminders.cancel(reminder_id(item)) end
  table.remove(items, index)
  if selected == item.id then selected = nil end
  save()
end

function on_event(e)
  notice = nil

  if e.type == "input_changed" and e.id == "draft" then
    draft = e.value

  elseif e.type == "date_changed" then
    if e.id == "draft_due" then
      draft_due = e.value
    elseif e.id == "due" then
      local item = current()
      if item then
        item.due = e.value
        sync(item)
        save()
      end
    end

  elseif e.type == "toggle" then
    -- Toggle ids on the list are "done:<id>", so the number says which item.
    local id = tonumber(e.id:match("^done:(%d+)$"))
    if id then
      local item = find(id)
      if item then set_done(item, e.value) end
    elseif e.id == "draft_remind" then
      draft_remind = e.value
    elseif e.id == "remind" then
      local item = current()
      if item then
        item.remind = e.value
        sync(item)
        save()
      end
    elseif e.id == "detail_done" then
      local item = current()
      if item then set_done(item, e.value) end
    end

  elseif e.type == "click" then
    if e.id == "add" and draft ~= "" then
      local item = {
        id = next_id,
        text = draft,
        done = false,
        due = draft_due,
        remind = draft_due ~= nil and draft_remind,
      }
      next_id = next_id + 1
      items[#items + 1] = item
      sync(item)
      draft = ""
      draft_due = nil
      wm.ui.set_input("draft", "")
      save()

    elseif e.id == "clear_done" then
      local kept = {}
      for _, item in ipairs(items) do
        if not item.done then kept[#kept + 1] = item end
      end
      items = kept
      save()

    elseif e.id == "previous" then
      step(-1)
    elseif e.id == "next" then
      step(1)
    elseif e.id == "delete" then
      local item = current()
      if item then remove(item) end
    end
  end
end

-- ----------------------------------------------------------------- render ---

local function list_page()
  local rows = {}

  rows[#rows + 1] = ui.row {
    ui.input { id = "draft", placeholder = "Something to do" },
    ui.button { id = "add", text = "Add", style = "primary" },
  }
  rows[#rows + 1] = ui.date { id = "draft_due", label = "Due (optional)", value = draft_due, time = true }
  if draft_due and wm.reminders then
    rows[#rows + 1] = ui.toggle { id = "draft_remind", label = "Remind me when it is due", checked = draft_remind }
  end
  rows[#rows + 1] = ui.divider()
  if notice then rows[#rows + 1] = ui.label { text = notice, style = "caption" } end

  if #items == 0 then
    rows[#rows + 1] = ui.label { text = "Nothing on the list.", style = "caption" }
    return rows
  end

  local remaining, late = 0, 0
  for _, item in ipairs(ordered()) do
    if not item.done then remaining = remaining + 1 end
    if overdue(item) then late = late + 1 end
    local line = status(item)
    rows[#rows + 1] = ui.toggle {
      id = "done:" .. item.id,
      label = line and (item.text .. "\n" .. line) or item.text,
      checked = item.done,
    }
  end
  local summary = remaining .. " of " .. #items .. " left"
  if late > 0 then summary = summary .. ", " .. late .. " overdue" end
  rows[#rows + 1] = ui.label { text = summary, style = "caption" }
  rows[#rows + 1] = ui.button { id = "clear_done", text = "Clear finished" }
  return rows
end

local function details_page()
  local item = current()
  if not item then
    return { ui.label { text = "Add something on the List page first.", style = "caption" } }
  end
  local list = ordered()
  local at = 1
  for index, other in ipairs(list) do
    if other == item then at = index end
  end

  local rows = {}
  rows[#rows + 1] = ui.row {
    ui.button { id = "previous", text = "‹ Previous", enabled = #list > 1 },
    ui.label { text = at .. " of " .. #list, style = "caption" },
    ui.button { id = "next", text = "Next ›", enabled = #list > 1 },
  }
  rows[#rows + 1] = ui.label { text = item.text, style = "title" }
  if notice then rows[#rows + 1] = ui.label { text = notice, style = "caption" } end
  rows[#rows + 1] = ui.date { id = "due", label = "Due", value = item.due, time = true }
  if wm.reminders then
    if item.due then
      rows[#rows + 1] = ui.toggle { id = "remind", label = "Remind me when it is due", checked = item.remind }
    else
      rows[#rows + 1] = ui.label { text = "Set a due date to get a reminder.", style = "caption" }
    end
  end
  rows[#rows + 1] = ui.toggle { id = "detail_done", label = "Done", checked = item.done }
  rows[#rows + 1] = ui.button { id = "delete", text = "Delete" }
  return rows
end

function render()
  local list = list_page()
  list.title = "List"
  local details = details_page()
  details.title = "Details"
  return ui.tabs { id = "pages", ui.page(list), ui.page(details) }
end
