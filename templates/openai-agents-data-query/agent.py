"""OpenAI Agents SDK agent with one Jo-backed database tool."""

import argparse
import os

from agents import Agent, Runner, function_tool

from jo_bridge import SYSTEM_PROMPT, run_jo


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("prompt")
    parser.add_argument("--user-id", type=int, default=1)
    args = parser.parse_args()

    @function_tool
    def run_jo_program(code: str) -> str:
        """Compile and run Jo code against this user's document database."""
        return run_jo(code, args.user_id)

    agent = Agent(
        name="Document analyst",
        instructions=SYSTEM_PROMPT,
        model=os.environ.get("JO_AGENT_MODEL", "gpt-5.6-terra"),
        tools=[run_jo_program],
    )
    print(Runner.run_sync(agent, args.prompt).final_output)


if __name__ == "__main__":
    main()
