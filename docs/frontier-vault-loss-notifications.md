# Frontier vault-loss login notices

Requirement: guild members who were offline when their founding vault fell receive a persistent, individual notice on their next successful join, including the attributed attacker when known and that all claims were lost. Online members retain the live defeat notification. This uses only Frontier and does not enable Discord delivery.

Implementation: snapshot the guild roster at defeat, save offline recipients' notices atomically to a separate Frontier YAML inbox, and deliver each after a short join delay. Acknowledgment is per notice and occurs after sending; a disconnect before delivery leaves the notice pending. Missing attacker attribution is explicitly reported as unknown. Notices are addressed to the members at the time of defeat, even if they later leave the guild. A crash or storage failure between the existing claim-release transaction and saving this separate file can still lose the notice; errors are logged. Delivery is at least once if the process crashes between sending and saving acknowledgment.

Verification: the new persistence regression was compiled before implementation and failed because the inbox did not exist. Focused persistence and login-listener tests and a Shadow JAR build are run locally; see the turn response for final results. No live-server or in-game verification is implied. Bloom remains unchanged.
