package example.products;

public record Product(long id, String sku) {
    public Product validated() {
        if (id < 1 || sku == null || sku.isBlank() || sku.length() > 80) {
            throw new IllegalArgumentException("Positive id and SKU of 1–80 characters required");
        }
        return this;
    }
}
