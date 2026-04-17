# Multi-Feature Enhancement Design

## 1. Cache / Config-Change Data Protection

**Problem:** InventoryFragment and ReportFragment store data in local lists. On config change (rotation), the Fragment is destroyed/recreated, lists are lost, data must be re-fetched.

**Solution:** Use ViewModel + SavedStateHandle for both fragments. This is already the pattern used by ClassicViewModel and ChatViewModel in this project.

- Create `InventoryViewModel` extending `AndroidViewModel`, move `inventoryList`, `lowStockList`, `recentActivityList`, `filteredInventoryList` into it. Add a `loadData()` method that calls the existing API.
- Create `ReportViewModel` similarly for `monthlyBills` and chart data.
- Fragments observe LiveData from ViewModel, survive rotation.

## 2. Inventory Popup Menu — Rounded Corner Custom Dialog

**Problem:** Default `PopupMenu` is rectangular, looks "plastic".

**Solution:** Replace `PopupMenu` with a custom `AlertDialog` using rounded-corner drawable background, with styled menu items. Same pattern already used for consume/replenish/delete dialogs in the app (`CustomDialogStyle`).

- Create a small dialog layout with rounded background.
- Show edit/delete as styled rows with icons.

## 3. Home Layout — Balanced Proportions

**Problem:** The classic mode bill list has a 200dp illustration at top + large stat cards, pushing the actual bill list far down. AddBill and Report are full separate tabs taking 2 of 5 bottom nav slots.

**Solution:** Reduce the header illustration from 200dp to 100dp, make stat cards more compact. This is a pure layout XML change — no logic changes.

## 4. Recipe Feature

**Problem:** Need a recipe management feature on the home page, integrated with inventory consumption.

**Solution:**

### Database
New `recipes` table:
- `recipe_id` INT AUTO_INCREMENT PK
- `user_id` INT NOT NULL FK
- `relationship_id` INT UNSIGNED NULL FK
- `title` VARCHAR(100) NOT NULL
- `description` TEXT NULL
- `image_url` VARCHAR(500) NULL
- `steps` TEXT NULL (JSON array of step strings)
- `created_at` / `updated_at` DATETIME

New `recipe_ingredients` table:
- `id` INT AUTO_INCREMENT PK
- `recipe_id` INT NOT NULL FK
- `inventory_id` INT NULL FK (nullable — can be free-text ingredient not linked to inventory)
- `ingredient_name` VARCHAR(100) NOT NULL
- `quantity` DECIMAL(10,2) DEFAULT 0
- `unit` VARCHAR(20)

### Server API
- `GET /api/recipes?userId=` — list recipes (shared within relationship)
- `POST /api/recipes` — create recipe
- `PUT /api/recipes/:id` — update recipe
- `DELETE /api/recipes/:id?userId=` — delete recipe
- `POST /api/recipes/:id/cook` — cook a recipe: deducts quantities from linked inventory items

### Android
- New `RecipeFragment` accessed from home page navigation bar
- Recipe list with cards (image, title, ingredient count)
- Recipe detail/add/edit dialogs
- "Cook" button that calls the cook endpoint, shows what will be consumed
- Home page gets a horizontal recipe navigation bar at the top

### Inventory Consumption Interface
The `POST /api/recipes/:id/cook` endpoint:
1. Looks up all `recipe_ingredients` for the recipe
2. For each ingredient linked to an `inventory_id`, calls the existing consume logic
3. Returns a summary of what was consumed and any insufficient-stock warnings
4. This is the "reserved interface" — the Android recipe UI just calls this one endpoint
