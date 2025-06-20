class Box {
public:
    Box(T value) : value_(value) {}

    void print() const {
        std::cout << "Boxed value: " << value_ << std::endl;
    }

private:
    T value_;
};

int main() {
    Box<int> b(42);
    b.print();

    std::vector<std::string> names = { "Alice", "Bob", "Charlie" };
    for (const auto& name : names) {
        std::cout << "Hello, " << name << std::endl;
    }

    return 0;
}