-- Beta funnel: shown -> accepted -> verified, from the opt-in events (run with psql against the backend database).
--
-- Run it 30 days after the last card was accepted: before that, some accepted cards are still inside their
-- follow-through window and count as "not verified" here. The app only sends VERIFIED when the swapped-to
-- category was new to the shopper's recent receipts (see FollowThroughChecker), so verified_new is conservative.
-- Retried uploads cannot inflate these numbers: event_id is the primary key.

with per_card as (
    select suggestion_id,
           max(swap_count)                as swap_count,
           max(kg_co2e_saved)             as kg_co2e_saved,
           bool_or(type = 'SHOWN')        as shown,
           bool_or(type = 'ACCEPTED')     as accepted,
           bool_or(type = 'DISMISSED')    as dismissed,
           bool_or(type = 'VERIFIED')     as verified
    from swap_event
    group by suggestion_id
)
select case when swap_count > 1 then 'bundle' else 'single swap' end                       as card_type,
       count(*) filter (where shown)                                                       as shown,
       count(*) filter (where accepted)                                                    as accepted,
       count(*) filter (where dismissed)                                                   as dismissed,
       round(100.0 * count(*) filter (where accepted)
             / nullif(count(*) filter (where accepted or dismissed), 0), 1)                as acceptance_pct,
       count(*) filter (where verified)                                                    as verified_new,
       round(100.0 * count(*) filter (where verified)
             / nullif(count(*) filter (where accepted), 0), 1)                             as follow_through_pct,
       round(coalesce(sum(kg_co2e_saved) filter (where verified), 0)::numeric, 1)          as kg_co2e_verified,
       count(distinct suggestion_id)                                                       as cards
from per_card
group by rollup (case when swap_count > 1 then 'bundle' else 'single swap' end)
order by card_type nulls last; -- the last row (card_type null) is the total
