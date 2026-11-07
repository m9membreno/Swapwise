package dev.swapwise.optimizer

/**
 * The one entry point the app calls: receipt in, swap cards out.
 *
 * The app should call [suggest] off the main thread (Dispatchers.Default): it is CPU-bound work.
 */
class SwapEngine(
    private val optimizer: BasketOptimizer = Nsga2Optimizer(),
    private val picker: SuggestionPicker = SuggestionPicker(),
) {
    fun suggest(problem: SwapProblem): List<Suggestion> {
        if (problem.lines.isEmpty()) return emptyList()
        return picker.pick(problem, optimizer.optimize(problem))
    }
}
