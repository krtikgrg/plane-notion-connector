# Repository instructions

- Save all modified files before finishing a code change.
- Format Java files with the VS Code Red Hat Java formatter configured for this workspace, using four spaces for indentation. Format other source files with the formatter configured for their language.
- Run the project's lint command on changed code files. If a language has no configured linter, say so explicitly and run its available compiler or type checker; do not describe that as linting.
- Review the final diff for formatting, indentation, and unintended changes before committing.
- Treat `backend/src/main/java/dev/planetonotion/models/` and `backend/src/main/java/dev/planetonotion/repositories/` as frozen. Do not edit, reformat, rename, move, or otherwise change files in either directory unless the user explicitly approves the specific change. If work appears to require a change in either directory, leave those files untouched and ask for approval before proceeding with that part.
- For UI end-to-end tests, run the backend and frontend directly; never use Docker or Compose in this chat. Before starting either server, check for existing processes/listeners, stop any older project servers, then start fresh backend and frontend processes.
