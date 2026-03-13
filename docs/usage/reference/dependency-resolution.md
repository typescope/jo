# Dependency Resolution

The resolver uses **Minimum Version Selection (MVS)**:

1. Collect all version constraints across the full dependency graph
2. For each package, select the maximum of all stated lower bounds
3. Verify the selected version satisfies every upper bound
4. Fail with a clear error if any constraint is violated

MVS is deterministic and requires no backtracking. Adding a dependency never silently upgrades unrelated packages. One version of each package is selected — matching Jo's single-namespace compilation model.
