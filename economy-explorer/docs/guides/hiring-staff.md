---
title: Hiring & managing staff
order: 3
description: Offer jobs, build a role hierarchy, and promote, demote, or remove employees.
---

# Hiring and managing staff

Once your firm exists you can bring other players on board, organise them into roles,
and control what each role is allowed to do. This guide assumes you've already
[created a firm](/docs/guides/setting-up-a-business) — examples use a firm called
`Acme`.

## Offer someone a job

Hiring is a two-step handshake: you send an offer, the player accepts it.

You (the owner, or anyone with the **ADMIN** role) send the offer:

```text
/business offer Acme Steve
```

`/business hire Acme Steve` does the same thing. Steve then accepts:

```text
/business offer accept Acme
```

> [!WARNING]
> Offers **expire after 5 minutes**. If it lapses, just send another. You can take one
> back early with `/business offer rescind Acme Steve`, and Steve can turn it down with
> `/business offer reject Acme`.

New hires start on your lowest role (**Employee** by default). See your team any time:

```text
/business staff list Acme
```

## Understand roles

Every employee holds one **role**, and each role grants a set of **permissions**. Your
firm came with five roles ready to go, from most senior (rank 1) to most junior (rank 5):

| Role | Rank | Default permission | What it can do |
|---|---|---|---|
| **Proprietor** | 1 (top) | `ADMIN` | Staff/role management and authorized spending; account administration stays proprietor-only |
| **Co-Proprietor** | 2 | `ADMIN` | Staff/role management and authorized spending; account administration stays proprietor-only |
| **Manager** | 3 | `FINANCIAL` | Read finances; spending also needs account authorization |
| **Supervisor** | 4 | `CHESTSHOP` | Run the firm's ChestShops |
| **Employee** | 5 (lowest) | `DEFAULT` | Basic membership — no special powers |

A **lower** rank number is more senior — `promote` moves someone toward rank 1, `demote`
toward rank 5. The firm **owner** can always do everything regardless of role.

The four permissions any role can hold:

| Permission | Lets the holder… |
|---|---|
| `ADMIN` | Manage staff/roles and authorized spending; not proprietor-only account controls |
| `FINANCIAL` | Move the firm's money — deposit, withdraw, and pay |
| `CHESTSHOP` | Create and use ChestShops that belong to the firm |
| `DEFAULT` | Nothing special — just be a member |

## Promote and demote

Move people up or down the ladder one rank at a time:

```text
/business staff promote Acme Steve
/business staff demote Acme Steve
```

## Build your own roles

The starter roles are a fine default, but you'll often want something tailored — say a
**Treasurer** who can handle money but not hire people. Create the role with a rank
number, then grant it a permission:

```text
/business staff role create Acme Treasurer 3
/business staff role permission add Acme Treasurer FINANCIAL
```

The number is the role's rank, used by promote/demote (lower number = more senior). Review
your roles and what each can do:

```text
/business staff role list Acme
/business staff role permission list Acme Treasurer
```

You can also `rename` and `delete` roles — see the
[Business reference](/docs/reference/business#roles).

## Remove someone

To let an employee go, or to leave a firm you work for:

```text
/business fire Acme Steve     # you remove an employee (needs ADMIN)
/business resign Acme         # you quit a firm you work for
```

## Where to go next

- Give your staff money to work with: **[Business accounts](/docs/guides/business-accounts)**.
- Every `/business` command: the **[Business reference](/docs/reference/business)**.
