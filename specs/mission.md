# Mission

A work sample for an **AI-first Senior Java Engineer (payments)** role in hotel payments. It shows, in miniature, two services such a platform needs: **posting payments to the hotel PMS (Oracle OPERA)** and **charging virtual cards**, each able to run beside an existing system before a hotel moves over.

It exists to show, in code and tests rather than claims:
- Java 17, Spring Boot and Hibernate on AWS, with card data handled to PCI standard: tokenisation, keys in KMS, nothing sensitive in logs
- the failure modes of money movement (retries, lost responses, outages, races) **proved** by tests
- how an AI-first engineer works: a written brief (`CLAUDE.md`), specs per capability, every agent change reviewed and proved, and a log of what the agents got wrong (`docs/agent-log.md`)

## Not goals
- Not any company's software, not affiliated with any hotel, vendor or processor. The processor and the PMS are simulated (in-memory fakes for tests, HTTP simulators for the full flow) behind real interfaces. All cards are public test numbers, all data is synthetic.
- No hosted payment page, 3-D Secure, or real card network traffic. No PCI certification is claimed; the controls are the ones a reviewer would look for.
- Not legal or compliance advice.
