# On-device language models reach production

A wave of consumer hardware now ships with a neural accelerator capable of running
a small language model without a network connection. The shift matters less for
raw capability than for latency, cost, and privacy: an assistant that answers from
the device does not send the user's text anywhere, and it keeps working on a
plane or in a tunnel.

The models involved are small by current standards, typically between one and
eight billion parameters. Quantization to four bits has become routine, and the
accuracy cost is now modest for everyday tasks such as summarization, rewriting,
and structured extraction.

## What changed in the hardware

Three things converged. Memory bandwidth on mobile parts roughly doubled over two
generations. Dedicated matrix-multiply units moved from niche to standard. And the
software stack matured, so a developer no longer hand-writes kernels for each
chip.

- Unified memory removes the copy between CPU and accelerator.
- Sparsity support skips zeroed weights at inference time.
- A common runtime format lets one model file target several vendors.

## What is still hard

Context windows remain short on device, so retrieval and careful prompt
construction do more work than they would with a large hosted model. Battery
draw under sustained generation is real, and thermal throttling can halve tokens
per second within a minute.

Fine-tuning on the device is mostly aspirational. The current pattern is to
personalize through retrieval and a small adapter downloaded from a server, not
through gradient updates on the handset.

## Outlook

The near-term winners are features that were previously too expensive to run per
keystroke: inline rewriting, on-the-fly translation, and local search over the
user's own files. The hosted frontier models are not threatened; the two are
becoming a tiered system, with the small local model handling the common case and
escalating only when it is unsure.
