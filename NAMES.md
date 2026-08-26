# Naming

Working name **Multiframe** cannot carry the app: it is descriptive (a
trademark examiner's word for "unregistrable"), and *MultiFrames* already
exists on Play. A descriptive name also undersells the thing — it names the
mechanism, not the result.

There is also a registration, which this document did not previously record.
**MULTIFRAME**, US serial 74107359, filed 19 October 1990 by Formation Design
Systems Pty Ltd, for "computer programs and instruction manuals sold therewith
in the field of engineering analysis" — structural analysis software that is
still sold. The field is not photography, so it is not necessarily fatal on its
own; both are Class 9 software, which is the part an examiner would weigh. It is
one more reason not to fight for a name that was already the weaker choice.

**What has and has not been checked.** The above came from ordinary web search,
and the Play developer *Multiframes* publishes in unrelated categories with no
camera app. That is a sighting exercise, not a clearance search: it did not
query the USPTO database directly, and covered no register outside the US.
Nothing here should be relied on for a filing decision — a real clearance on
whichever name is chosen wants the USPTO search proper, the equivalent registers
for any market that matters, and an attorney. The searching done for **Coadd**
turned up no conflicts, which is encouraging and is not the same as none
existing.

## What the name has to do

The product argument is narrower than "computational camera". It is:

> The computation is spent getting a **cleaner negative**, not a
> more *processed picture*.

Everything the app does — stacking 30 fps raw frames, merging in the Bayer
domain before anything is demosaiced, writing an untouched DNG — serves a
result that looks like it came off a camera, not out of a phone. The name
should sound like an instrument or a darkroom, not like a filter or an
assistant. Anything that reads as "smart", "AI", "magic", or "enhance" fights
the positioning.

Worth knowing: Halide shipped a "zero AI processing" mode in 2024 and it was
covered as news. There is demonstrated appetite for this stance, and it is
currently a **positioning that is available on Android**.

## Shortlist

Ordered by how well each carries the argument, not by preference.

### Coadd — recommended

Astronomers' term for adding aligned exposures so the signal accumulates and
the noise does not. It is, precisely and unglamorously, what this app does.

- Coined as far as consumers are concerned, so a **strong mark** — the opposite
  of Multiframe's problem
- No camera app conflict found on Play
- Signals *real science*, not marketing science. Borrowed from people pointing
  instruments at the sky, which is exactly the register the product wants
- Short, types easily, `coadd.app` class of domain plausible
- Against: "co-add" needs saying once before it is obvious

### f64 — the positioning pick

Group f/64 — Adams, Weston, Cunningham — formed in 1932 explicitly against the
soft, manipulated Pictorialist look of the day, arguing for sharp, unretouched
photography. The parallel is almost too neat: a movement named after a hard
technical setting, defined by refusing the fashionable processing of its era.

- Carries the entire product argument in three characters, for anyone who knows
- Deep, defensible heritage that is public domain, not a person's name
- Against: the "/" cannot go in a package id or most domains, so it becomes
  `f64`, which loses a little. Meaningless to those who do not know the history

### Quanta — the accessible pick

Light arrives as countable quanta; stacking frames is how you collect more of
them. The honest physical description of why the picture is cleaner.

- Most pronounceable and consumer-friendly on the list
- Suggests precision instrumentation without saying "pro"
- Against: *Quanta Magazine* is prominent, so search results are crowded and
  trademark clearance would need real checking in class 9

### Negative

The output is a DNG — a Digital NeGative. A raw-first pipeline that treats the
negative as the real artefact deserves the word.

- Single strong noun, contrarian in tone, memorable
- Ties directly to the architecture: the merged negative is the product, and
  both outputs are developed from it
- Against: unsearchable in a store, and every review headline writes itself
  badly ("Negative is a positive")

### MLTFRME — your instinct, and it holds up

- Coined, therefore **the strongest mark of any option here**
- Keeps the meaning for anyone who decodes it
- Looks like a brand rather than a description
- Against: unsayable aloud, so word of mouth breaks. Nobody can spell it into a
  search box from hearing it. Disemvowelling also reads as a 2010s startup tic,
  which sits oddly against a heritage-craft positioning

### Also considered

| Name | Why it was cut |
|---|---|
| Latent | The film metaphor is lovely — the invisible image before development — but "latent" now means *latent space*, and Latent AI exists. It reads as the thing we are positioning against |
| Plate | Glass-plate photography, nicely physical, but too generic a word to own |
| Zone XI | Adams' Zone System extended past pure white, meaning recovered headroom. Great story, too oblique |
| Halide, Kino, Obscura | Taken, all by well-regarded camera apps |
| Emulsion, Collodion | Right register, unspellable |
| Stack, Stackr | Descriptive again, which is the trap we are leaving |

## Recommendation

**Coadd**, with **f64** as the alternative if the heritage argument appeals more
than the instrument one.

Coadd wins on the combination that matters: it is legally strong because it is
meaningless to a consumer, and it is *emotionally* strong to exactly the person
who would pay for this app, because it is a real word used by people doing real
frame-stacking. It also has room to grow — the merge, the DNG and any future
astro mode all sit under it without strain.

Keep **MLTFRME** in reserve. If the app ever wants a louder, more graphic
identity, it is the better logo.

## Before committing to any of them

- [ ] Trademark search in class 9 (software) and class 42 in the target markets
- [ ] Play Store and App Store name search, including near-misses
- [ ] Domain, and the handle on at least one social platform
- [ ] Say it out loud to someone and have them spell it back
- [ ] Check it does not mean something unfortunate in a major market language

**The package id is the one thing that cannot change after publication.**
`dev.multiframe.camera` is already committed to in this repo. If the name
changes, the package id must change *before* first publication — after that it
is permanent, and a new id means a new listing with no reviews or installs.
