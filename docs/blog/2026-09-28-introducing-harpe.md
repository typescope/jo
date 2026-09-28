---
title: Introducing Harpe — An Agent Framework with Compile-Time Sandboxing
date: 2026-09-28
author: The Jo Team
description: Harpe is an agent framework with fine-grained permissions enforced through compile-time sandboxing.
---

# Introducing Harpe — An Agent Framework with Compile-Time Sandboxing

*September 28, 2026 · The Jo Team*

Today we are introducing **[Harpe](https://harpe.typescope.ai/)**, an open-source
agent framework for sensitive data and critical infrastructure, with
fine-grained permissions enforced through compile-time sandboxing.

For each action, the model writes a Jo program that is type checked against the
capabilities granted by the application before it runs. Undeclared authority is
rejected by the compiler, while credentials and sensitive resources remain
behind narrow, typed interfaces.

Harpe 0.13.0 is available now as a developer preview. Learn more, see examples,
and build your first agent at **[harpe.typescope.ai](https://harpe.typescope.ai/)**.
