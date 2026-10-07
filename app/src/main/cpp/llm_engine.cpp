#include "llm_engine.h"

#include <algorithm>
#include <vector>

#include "llama.h"

LlmEngine::~LlmEngine() {
    if (sampler_ != nullptr) {
        llama_sampler_free(sampler_);
    }
    if (ctx_ != nullptr) {
        llama_free(ctx_);
    }
    if (model_ != nullptr) {
        llama_model_free(model_);
    }
}

bool LlmEngine::load(const std::string &model_path, int context_tokens, int threads,
                     std::string *error) {
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_ = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model_ == nullptr) {
        *error = "model file could not be loaded: " + model_path;
        return false;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = context_tokens;
    ctx_params.n_batch = context_tokens;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    ctx_ = llama_init_from_model(model_, ctx_params);
    if (ctx_ == nullptr) {
        *error = "context could not be created";
        return false;
    }

    // Greedy: the same command always gives the same function call.
    sampler_ = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler_, llama_sampler_init_greedy());
    return true;
}

namespace {

// Special tokens written in the text are parsed as tokens, not as plain text.
bool tokenize(const llama_vocab *vocab, const std::string &text, bool add_bos,
              std::vector<llama_token> *tokens) {
    int count = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_bos,
                                true);
    tokens->resize(count);
    return llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens->data(),
                          (int32_t) tokens->size(), add_bos, true) >= 0;
}

}  // namespace

bool LlmEngine::hold_preamble(const std::string &preamble, std::string *error) {
    llama_memory_t memory = llama_get_memory(ctx_);
    // Usual case: drop the previous request and answer, keep the preamble.
    if (preamble == held_preamble_ && held_preamble_tokens_ > 0
        && llama_memory_seq_rm(memory, 0, held_preamble_tokens_, -1)) {
        return true;
    }
    held_preamble_.clear();
    held_preamble_tokens_ = 0;
    llama_memory_clear(memory, true);

    std::vector<llama_token> tokens;
    if (!tokenize(llama_model_get_vocab(model_), preamble, true, &tokens) || tokens.empty()) {
        *error = "preamble could not be tokenized";
        return false;
    }
    if (tokens.size() >= llama_n_ctx(ctx_)) {
        *error = "preamble is too long: " + std::to_string(tokens.size()) + " tokens";
        return false;
    }
    if (llama_decode(ctx_, llama_batch_get_one(tokens.data(), (int32_t) tokens.size())) != 0) {
        llama_memory_clear(memory, true);
        *error = "decoding the preamble failed";
        return false;
    }
    held_preamble_ = preamble;
    held_preamble_tokens_ = (int) tokens.size();
    return true;
}

bool LlmEngine::generate(const std::string &preamble, const std::string &request, int max_tokens,
                         const std::string &stop, std::string *output, std::string *error) {
    output->clear();
    if (ctx_ == nullptr) {
        *error = "model is not loaded";
        return false;
    }
    cancelled_ = false;
    const llama_vocab *vocab = llama_model_get_vocab(model_);

    std::vector<llama_token> tokens;
    if (!tokenize(vocab, request, false, &tokens) || tokens.empty()) {
        *error = "request could not be tokenized";
        return false;
    }
    if (!hold_preamble(preamble, error)) {
        return false;
    }
    if (held_preamble_tokens_ + (int) tokens.size() + max_tokens > (int) llama_n_ctx(ctx_)) {
        *error = "request is too long: " + std::to_string(tokens.size()) + " tokens";
        return false;
    }
    llama_sampler_reset(sampler_);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    llama_token next = 0;
    for (int generated = 0; generated < max_tokens && !cancelled_; generated++) {
        if (llama_decode(ctx_, batch) != 0) {
            // The context is in an unknown state: the next call starts from nothing.
            held_preamble_.clear();
            *error = "decoding failed";
            return false;
        }
        next = llama_sampler_sample(sampler_, ctx_, -1);
        if (llama_vocab_is_eog(vocab, next)) {
            break;
        }
        char piece[256];
        int length = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
        if (length < 0) {
            *error = "token could not be converted to text";
            return false;
        }
        output->append(piece, length);
        if (!stop.empty() && output->find(stop) != std::string::npos) {
            break;
        }
        batch = llama_batch_get_one(&next, 1);
    }
    return true;
}

void LlmEngine::cancel() {
    cancelled_ = true;
}

std::string LlmEngine::chat_template() const {
    const char *chat_template = model_ == nullptr ? nullptr : llama_model_chat_template(model_, nullptr);
    return chat_template == nullptr ? "" : chat_template;
}
