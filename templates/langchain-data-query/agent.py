"""LangChain agent with one Jo-backed database tool."""

import argparse
import os

from langchain.agents import create_agent
from langchain.tools import tool

from jo_bridge import SYSTEM_PROMPT, run_jo


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("prompt")
    parser.add_argument("--user-id", type=int, default=1)
    args = parser.parse_args()

    @tool
    def run_jo_program(code: str) -> str:
        """Compile and run Jo code against this user's document database."""
        return run_jo(code, args.user_id)

    agent = create_agent(
        model=os.environ.get("MODEL", "openai:gpt-5.6-terra"),
        tools=[run_jo_program],
        system_prompt=SYSTEM_PROMPT,
    )
    result = agent.invoke({"messages": [{"role": "user", "content": args.prompt}]})
    print(result["messages"][-1].content)


if __name__ == "__main__":
    main()
