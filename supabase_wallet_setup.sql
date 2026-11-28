-- =========================================================
-- SELFBUY ONLINE WALLET (ZERO BONUS, NO SBC, AUTH-GATED)
-- Run this entire script in Supabase -> SQL Editor -> New Query
-- =========================================================

-- 1. WALLETS TABLE (Starts strictly at $0.00 USD)
create table if not exists public.wallets (
  user_id uuid primary key references auth.users(id) on delete cascade,
  email text,
  uid_code text unique not null,
  balance_usd numeric(12,2) default 0.00 not null check (balance_usd >= 0),
  active_pkg text default 'FREE TIER' not null,
  discount_pct int default 0 not null,
  transfer_pin text default '1234' not null,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

-- 2. DEPOSIT REQUESTS TABLE
create table if not exists public.deposit_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade not null,
  amount_usd numeric(12,2) not null check (amount_usd > 0),
  method text not null,
  tx_ref text not null,
  status text default 'pending' not null check (status in ('pending', 'approved', 'rejected')),
  created_at timestamptz default now()
);

-- 3. WALLET TRANSACTIONS TABLE
create table if not exists public.wallet_transactions (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid references auth.users(id),
  receiver_id uuid references auth.users(id),
  tx_type text not null, -- 'deposit', 'transfer', 'package'
  amount_usd numeric(12,2) not null,
  note text,
  created_at timestamptz default now()
);

-- 4. AUTO-CREATE $0.00 WALLET ON NEW USER SIGNUP
create or replace function public.handle_new_wallet_user()
returns trigger as $$
declare
  v_uid text;
  v_pin text;
begin
  v_uid := 'SB-' || floor(100000 + random() * 900000)::int::text;
  v_pin := coalesce(nullif(new.raw_user_meta_data->>'transfer_pin', ''), '1234');
  insert into public.wallets (user_id, email, uid_code, balance_usd, transfer_pin)
  values (new.id, new.email, v_uid, 0.00, v_pin)
  on conflict (user_id) do nothing;
  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists on_auth_user_created_wallet on auth.users;
create trigger on_auth_user_created_wallet
  after insert on auth.users
  for each row execute procedure public.handle_new_wallet_user();

-- 5. ROW LEVEL SECURITY (Users can never edit their own balance)
alter table public.wallets enable row level security;
alter table public.deposit_requests enable row level security;
alter table public.wallet_transactions enable row level security;

drop policy if exists "Users read own wallet" on public.wallets;
create policy "Users read own wallet" on public.wallets
  for select using (auth.uid() = user_id);

drop policy if exists "Users read own deposits" on public.deposit_requests;
create policy "Users read own deposits" on public.deposit_requests
  for select using (auth.uid() = user_id);

drop policy if exists "Users create own deposits" on public.deposit_requests;
create policy "Users create own deposits" on public.deposit_requests
  for insert with check (auth.uid() = user_id and status = 'pending');

drop policy if exists "Users read own transactions" on public.wallet_transactions;
create policy "Users read own transactions" on public.wallet_transactions
  for select using (auth.uid() = sender_id or auth.uid() = receiver_id);

-- 6. SECURE ATOMIC INTERNAL TRANSFER RPC
create or replace function public.internal_transfer(
  p_receiver text,
  p_amount numeric,
  p_pin text
) returns json as $$
declare
  v_sender_id uuid := auth.uid();
  v_sender record;
  v_receiver record;
begin
  if v_sender_id is null then
    return json_build_object('ok', false, 'error', 'Please sign in first');
  end if;
  if p_amount is null or p_amount <= 0 then
    return json_build_object('ok', false, 'error', 'Invalid transfer amount');
  end if;

  select * into v_sender from public.wallets where user_id = v_sender_id for update;
  if not found then
    return json_build_object('ok', false, 'error', 'Wallet not found');
  end if;
  if v_sender.transfer_pin <> p_pin then
    return json_build_object('ok', false, 'error', 'Incorrect 4-digit PIN');
  end if;
  if v_sender.balance_usd < p_amount then
    return json_build_object('ok', false, 'error', 'Insufficient balance. Please deposit funds first.');
  end if;

  select * into v_receiver from public.wallets
   where upper(uid_code) = upper(trim(p_receiver))
      or lower(email) = lower(trim(p_receiver))
   for update;

  if not found then
    return json_build_object('ok', false, 'error', 'Recipient UID or email not found');
  end if;
  if v_receiver.user_id = v_sender_id then
    return json_build_object('ok', false, 'error', 'You cannot transfer funds to yourself');
  end if;

  update public.wallets set balance_usd = balance_usd - p_amount, updated_at = now()
   where user_id = v_sender_id;
  update public.wallets set balance_usd = balance_usd + p_amount, updated_at = now()
   where user_id = v_receiver.user_id;

  insert into public.wallet_transactions(sender_id, receiver_id, tx_type, amount_usd, note)
  values (v_sender_id, v_receiver.user_id, 'transfer', p_amount, 'Transfer to ' || v_receiver.uid_code);

  return json_build_object('ok', true, 'message', 'Transfer completed successfully');
end;
$$ language plpgsql security definer;

-- 7. SECURE PACKAGE ACTIVATION RPC
create or replace function public.buy_package(
  p_pkg_name text,
  p_cost numeric,
  p_discount int
) returns json as $$
declare
  v_user_id uuid := auth.uid();
  v_wallet record;
begin
  if v_user_id is null then
    return json_build_object('ok', false, 'error', 'Please sign in first');
  end if;

  select * into v_wallet from public.wallets where user_id = v_user_id for update;
  if not found then
    return json_build_object('ok', false, 'error', 'Wallet not found');
  end if;
  if v_wallet.active_pkg = p_pkg_name then
    return json_build_object('ok', false, 'error', p_pkg_name || ' is already active');
  end if;
  if v_wallet.balance_usd < p_cost then
    return json_build_object('ok', false, 'error', 'Insufficient balance. Please deposit funds first.');
  end if;

  update public.wallets
     set balance_usd = balance_usd - p_cost,
         active_pkg = p_pkg_name,
         discount_pct = p_discount,
         updated_at = now()
   where user_id = v_user_id;

  insert into public.wallet_transactions(sender_id, tx_type, amount_usd, note)
  values (v_user_id, 'package', p_cost, 'Activated ' || p_pkg_name || ' (' || p_discount || '% Store Discount)');

  return json_build_object('ok', true, 'message', p_pkg_name || ' activated successfully');
end;
$$ language plpgsql security definer;

-- 8. ADMIN HELPER: APPROVE A USER DEPOSIT
-- Usage: select public.approve_deposit('PASTE-DEPOSIT-UUID-HERE');
create or replace function public.approve_deposit(p_deposit_id uuid)
returns json as $$
declare
  v_dep record;
begin
  select * into v_dep from public.deposit_requests where id = p_deposit_id for update;
  if not found then
    return json_build_object('ok', false, 'error', 'Deposit request not found');
  end if;
  if v_dep.status <> 'pending' then
    return json_build_object('ok', false, 'error', 'Deposit request has already been processed');
  end if;

  update public.deposit_requests set status = 'approved' where id = p_deposit_id;
  update public.wallets set balance_usd = balance_usd + v_dep.amount_usd, updated_at = now()
   where user_id = v_dep.user_id;

  insert into public.wallet_transactions(receiver_id, tx_type, amount_usd, note)
  values (v_dep.user_id, 'deposit', v_dep.amount_usd, 'Deposit via ' || v_dep.method || ' (' || v_dep.tx_ref || ')');

  return json_build_object('ok', true, 'message', 'Deposit approved and balance credited');
end;
$$ language plpgsql security definer;
