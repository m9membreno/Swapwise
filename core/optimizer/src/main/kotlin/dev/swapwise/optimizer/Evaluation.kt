package dev.swapwise.optimizer

/** Computes the objectives of a choice vector. Sums always run in line order, so results are deterministic. */
internal object Evaluator {
    fun evaluate(problem: SwapProblem, choices: IntArray): Objectives {
        require(choices.size == problem.lines.size) { "Expected ${problem.lines.size} choices, got ${choices.size}" }
        var cost = 0L
        var carbon = 0.0
        var disruption = 0.0
        for (i in choices.indices) {
            val line = problem.lines[i]
            val option = line.options[choices[i]]
            cost += option.product.unitPriceCents * line.quantity
            carbon += option.product.kgCo2ePerUnit * line.quantity
            disruption += option.familiarity.disruption
        }
        return Objectives(cost, carbon, disruption)
    }

    fun costOf(problem: SwapProblem, choices: IntArray): Long {
        var cost = 0L
        for (i in choices.indices) {
            cost += problem.lines[i].options[choices[i]].product.unitPriceCents * problem.lines[i].quantity
        }
        return cost
    }

    fun score(problem: SwapProblem, choices: IntArray): ScoredBasket =
        ScoredBasket(choices.toList(), evaluate(problem, choices))
}

/** Pareto dominance and sorting helpers shared by the optimizers. */
internal object Pareto {
    /** True if [a] is at least as good as [b] on every objective and strictly better on at least one. */
    fun dominates(a: Objectives, b: Objectives): Boolean {
        val noWorse = a.costCents <= b.costCents && a.kgCo2e <= b.kgCo2e && a.disruption <= b.disruption
        val better = a.costCents < b.costCents || a.kgCo2e < b.kgCo2e || a.disruption < b.disruption
        return noWorse && better
    }

    /**
     * Fast non-dominated sort (Deb et al., 2002). Returns fronts as lists of indices into [objectives]:
     * front 0 holds everything nobody dominates, front 1 is dominated only by front 0, and so on.
     * O(M * N^2) for N points and M = 3 objectives.
     */
    fun nonDominatedSort(objectives: List<Objectives>): List<List<Int>> {
        val n = objectives.size
        val dominatedBy = IntArray(n) // how many points dominate i
        val dominates = Array(n) { ArrayList<Int>() } // which points i dominates
        val fronts = ArrayList<MutableList<Int>>()
        val first = ArrayList<Int>()
        for (p in 0 until n) {
            for (q in p + 1 until n) {
                if (dominates(objectives[p], objectives[q])) {
                    dominates[p].add(q)
                    dominatedBy[q]++
                } else if (dominates(objectives[q], objectives[p])) {
                    dominates[q].add(p)
                    dominatedBy[p]++
                }
            }
        }
        for (p in 0 until n) if (dominatedBy[p] == 0) first.add(p)
        var current: List<Int> = first
        while (current.isNotEmpty()) {
            fronts.add(current.toMutableList())
            val next = ArrayList<Int>()
            for (p in current) {
                for (q in dominates[p]) {
                    dominatedBy[q]--
                    if (dominatedBy[q] == 0) next.add(q)
                }
            }
            current = next
        }
        return fronts
    }

    /**
     * Crowding distance for the points of one front: how much empty space surrounds each point,
     * summed over objectives and normalized by each objective's range. Boundary points get infinity
     * so the extremes (for example, the lowest-carbon basket) are never thrown away.
     */
    fun crowdingDistance(front: List<Int>, objectives: List<Objectives>): Map<Int, Double> {
        val distance = HashMap<Int, Double>(front.size * 2)
        front.forEach { distance[it] = 0.0 }
        if (front.size <= 2) {
            front.forEach { distance[it] = Double.POSITIVE_INFINITY }
            return distance
        }
        val axes: List<(Objectives) -> Double> = listOf(
            { it.costCents.toDouble() },
            { it.kgCo2e },
            { it.disruption },
        )
        for (axis in axes) {
            val sorted = front.sortedBy { axis(objectives[it]) }
            val min = axis(objectives[sorted.first()])
            val max = axis(objectives[sorted.last()])
            distance[sorted.first()] = Double.POSITIVE_INFINITY
            distance[sorted.last()] = Double.POSITIVE_INFINITY
            val range = max - min
            if (range == 0.0) continue
            for (k in 1 until sorted.size - 1) {
                val idx = sorted[k]
                val current = distance.getValue(idx)
                if (current == Double.POSITIVE_INFINITY) continue
                val gap = axis(objectives[sorted[k + 1]]) - axis(objectives[sorted[k - 1]])
                distance[idx] = current + gap / range
            }
        }
        return distance
    }

    /** Keeps only non-dominated baskets, drops duplicate choice vectors, and sorts by carbon then cost. */
    fun front(baskets: List<ScoredBasket>): List<ScoredBasket> {
        val unique = baskets.distinctBy { it.choices }
        return unique
            .filter { candidate -> unique.none { other -> dominates(other.objectives, candidate.objectives) } }
            .sortedWith(BY_CARBON_THEN_COST)
    }

    val BY_CARBON_THEN_COST: Comparator<ScoredBasket> =
        compareBy<ScoredBasket>({ it.objectives.kgCo2e }, { it.objectives.costCents }, { it.objectives.disruption })
}
