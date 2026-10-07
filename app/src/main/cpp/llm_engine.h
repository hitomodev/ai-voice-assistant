#pragma once

#include <atomic>
#include <string>

struct llama_model;
struct llama_context;
struct llama_sampler;

// One loaded model and one context, for one generation at a time. Not thread-safe, except for
// cancel().
class LlmEngine {
public:
    LlmEngine() = default;
    ~LlmEngine();
    LlmEngine(const LlmEngine &) = delete;
    LlmEngine &operator=(const LlmEngine &) = delete;

    // Returns false and fills error if the model cannot be loaded.
    bool load(const std::string &model_path, int context_tokens, int threads, std::string *error);

    // Generates the continuation of preamble + request, which must hold the model's own turn
    // markers. The preamble is the part that is the same for every request: it is computed once
    // and kept, so only the request costs time on later calls. It must end with a special token
    // and must not be empty, and the request must not be empty.
    // The output keeps the model's special tokens as text. It ends at the end-of-turn token,
    // after stop (if not empty) appears, at max_tokens, or on cancel().
    // Returns false and fills error on failure.
    bool generate(const std::string &preamble, const std::string &request, int max_tokens,
                  const std::string &stop, std::string *output, std::string *error);

    // Makes a generation in progress return early. Safe to call from any thread.
    void cancel();

    // The chat template stored in the model file, or an empty string.
    std::string chat_template() const;

private:
    // Makes the context hold exactly the preamble. Returns false and fills error on failure.
    bool hold_preamble(const std::string &preamble, std::string *error);

    llama_model *model_ = nullptr;
    llama_context *ctx_ = nullptr;
    llama_sampler *sampler_ = nullptr;
    std::atomic<bool> cancelled_{false};
    // The preamble the context starts with, and its length in tokens. Empty if none.
    std::string held_preamble_;
    int held_preamble_tokens_ = 0;
};
