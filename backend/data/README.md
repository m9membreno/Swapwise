# Carbon factor data

The backend serves one carbon factor per food category (see `FoodCategory.kt`). Each factor is built from
[Agribalyse](https://doc.agribalyse.fr/documentation-en/agribalyse-data/data-access) products:

* **mean:** the average kg CO2e per kg of the products mapped to the category;
* **range (low to high):** the lowest and highest of those products, shown to users instead of one number.

## Filling in `category-mapping.csv`

The file lists every category with an empty `agribalyse_codes` column. For each category:

1. Download the Agribalyse synthesis table (CSV) from the link above.
2. Pick 3 to 10 products that represent what people actually buy in that category (for example, for `BEEF`:
   ground beef, steak, roast). Use the "Code AGB" column.
3. Put the codes in `agribalyse_codes`, separated by `|`.
4. Fill `typical_item_kg` only when one item has a typical weight (one egg, one loaf of bread).

Categories left empty are skipped and reported by the import; the app shows "no estimate" for them.

## Running the import

```bash
./gradlew bootJar
java -jar build/libs/swapwise-backend-0.1.0-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --swapwise.import.agribalyse-file=/path/to/agribalyse.csv \
  --swapwise.import.mapping-file=data/category-mapping.csv \
  --swapwise.import.delimiter=';'
```

The import checks everything before writing (unknown categories, codes not in the file, bad numbers) and
publishes a new release in one transaction. If the column names differ in your Agribalyse version, update
`AgribalyseColumns` in `FactorBuilder.kt`.
