use nanofaas::{BoxError, Context, Runtime};
use serde::Deserialize;

#[derive(Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
struct Work {
    iterations: u64,
    working_set_bytes: usize,
    seed: u64,
}

fn compute(input: Work) -> Result<u64, BoxError> {
    if input.iterations > 1_000_000_000 || input.working_set_bytes > 128 * 1024 * 1024 {
        return Err("workload exceeds bounded CPU or memory input".into());
    }
    let mut memory = vec![0u8; input.working_set_bytes];
    let mut checksum = input.seed;
    // Touch every byte, including when iterations is zero. The checksum consumes the writes.
    for (i, byte) in memory.iter_mut().enumerate() {
        *byte = (input.seed.wrapping_add(i as u64)) as u8;
    }
    for i in 0..input.iterations {
        checksum = checksum
            .wrapping_mul(6364136223846793005)
            .wrapping_add(i ^ 1442695040888963407);
        if !memory.is_empty() {
            let index = checksum as usize % memory.len();
            memory[index] = memory[index].wrapping_add((checksum >> 32) as u8);
            checksum ^= memory[index] as u64;
        }
    }
    for byte in std::hint::black_box(memory) {
        checksum = checksum.rotate_left(5) ^ byte as u64;
    }
    Ok(std::hint::black_box(checksum))
}
async fn handle(ctx: Context, input: Work) -> Result<u64, BoxError> {
    ctx.spawn_blocking(move || compute(input)).await?
}
#[tokio::main]
async fn main() {
    if let Err(error) = Runtime::from_env()
        .register("one-shot-workload", handle)
        .start()
        .await
    {
        eprintln!("{error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn deterministic_and_bounded() {
        let input = Work {
            iterations: 10_000,
            working_set_bytes: 4096,
            seed: 42,
        };
        assert_eq!(compute(input).unwrap(), compute(input).unwrap());
        assert!(
            compute(Work {
                working_set_bytes: 128 * 1024 * 1024 + 1,
                ..input
            })
            .is_err()
        );
        assert!(
            compute(Work {
                iterations: 1_000_000_001,
                ..input
            })
            .is_err()
        );
    }
}
