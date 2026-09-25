/// <reference path="../pb_data/types.d.ts" />
// LabelScan schema for PocketBase 0.22.x. Auto-runs on first boot.
// Two collections mirror the phone's local SQLite: one row per UPC (keyed by UPC,
// so imports fill blanks and never duplicate) and one record per attached image.
// Every rule requires an authenticated app user.

migrate(
  (db) => {
    const dao = new Dao(db);
    const auth = '@request.auth.id != ""';
    const txt = (name, required) =>
      new SchemaField({ name, type: "text", required: !!required, options: {} });
    const num = (name) =>
      new SchemaField({ name, type: "number", required: false, options: {} });

    const products = new Collection({
      name: "products",
      type: "base",
      listRule: auth,
      viewRule: auth,
      createRule: auth,
      updateRule: auth,
      deleteRule: auth,
      schema: [
        txt("upc", true),
        txt("name"),
        txt("category"),
        txt("item_no"),
        txt("size"),
        txt("dept"),
        txt("plu"),
        txt("last_slot"),
        txt("price"),
        txt("unit_price"),
        txt("notes"),
        num("times_seen"),
        num("first_seen"),
        num("last_seen"),
      ],
      indexes: ["CREATE UNIQUE INDEX idx_products_upc ON products (upc)"],
    });
    dao.saveCollection(products);

    const photos = new Collection({
      name: "photos",
      type: "base",
      listRule: auth,
      viewRule: auth,
      createRule: auth,
      updateRule: auth,
      deleteRule: auth,
      schema: [
        txt("upc", true),
        txt("kind"),
        txt("note"),
        num("created_ms"),
        new SchemaField({
          name: "image",
          type: "file",
          required: false,
          options: {
            maxSelect: 1,
            maxSize: 8388608, // 8 MB — stored scans are ~0.3 MB, this is generous headroom
            mimeTypes: ["image/jpeg", "image/png", "image/webp"],
          },
        }),
      ],
      indexes: ["CREATE INDEX idx_photos_upc ON photos (upc)"],
    });
    dao.saveCollection(photos);
  },
  (db) => {
    const dao = new Dao(db);
    for (const name of ["photos", "products"]) {
      try {
        dao.deleteCollection(dao.findCollectionByNameOrId(name));
      } catch (_) {}
    }
  }
);
