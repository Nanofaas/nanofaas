//! Five bounded integer workloads shared by distinct warm function identities.
use nanofaas::BoxError;
use serde::Deserialize;
use std::hint::black_box;

const MULTIPLIER: u64 = 6364136223846793005;
const INCREMENT: u64 = 1442695040888963407;

#[derive(Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Arithmetic {
    pub iterations: u64,
    pub seed: u64,
}
#[derive(Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Sorting {
    pub items: usize,
    pub passes: u64,
    pub seed: u64,
}
#[derive(Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MemoryScan {
    pub working_set_bytes: usize,
    pub passes: u64,
    pub seed: u64,
}
#[derive(Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Matrix {
    pub dimension: usize,
    pub repetitions: u64,
    pub seed: u64,
}
#[derive(Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Mixed {
    pub arithmetic_iterations: u64,
    pub sort_items: usize,
    pub sort_passes: u64,
    pub working_set_bytes: usize,
    pub memory_passes: u64,
    pub matrix_dimension: usize,
    pub matrix_repetitions: u64,
    pub seed: u64,
}

impl Arithmetic {
    fn validate(self) -> Result<(), BoxError> {
        if self.iterations > 1_000_000_000 {
            return Err("arithmetic work exceeds bounds".into());
        }
        Ok(())
    }
}
impl Sorting {
    fn validate(self) -> Result<(), BoxError> {
        if self.items > 1_000_000
            || self.passes > 1_000_000
            || (self.items as u64)
                .checked_mul(self.passes)
                .is_none_or(|n| n > 100_000_000)
        {
            return Err("sorting work exceeds bounds".into());
        }
        Ok(())
    }
}
impl MemoryScan {
    fn validate(self) -> Result<(), BoxError> {
        if self.working_set_bytes > 64 * 1024 * 1024
            || self.passes > 1_000_000
            || (self.working_set_bytes as u64)
                .checked_mul(self.passes)
                .is_none_or(|n| n > 1_000_000_000)
        {
            return Err("memory scan work exceeds bounds".into());
        }
        Ok(())
    }
}
impl Matrix {
    fn validate(self) -> Result<(), BoxError> {
        if self.dimension > 256
            || self.repetitions > 1_000_000
            || (self.dimension as u64)
                .checked_pow(3)
                .and_then(|n| n.checked_mul(self.repetitions))
                .is_none_or(|n| n > 1_000_000_000)
        {
            return Err("matrix work exceeds bounds".into());
        }
        Ok(())
    }
}

pub fn arithmetic(input: Arithmetic) -> Result<u64, BoxError> {
    input.validate()?;
    let mut result = input.seed;
    for index in 0..input.iterations {
        result = result
            .wrapping_mul(MULTIPLIER)
            .wrapping_add(index ^ INCREMENT);
    }
    Ok(black_box(result))
}

pub fn sorting(input: Sorting) -> Result<u64, BoxError> {
    input.validate()?;
    let mut state = input.seed;
    let mut values: Vec<u64> = (0..input.items)
        .map(|index| {
            state = state
                .wrapping_mul(MULTIPLIER)
                .wrapping_add(index as u64 ^ INCREMENT);
            state
        })
        .collect();
    for pass in 0..input.passes {
        black_box(&mut values).sort_unstable();
        for value in &mut values {
            *value = value.wrapping_mul(MULTIPLIER).wrapping_add(pass);
        }
    }
    Ok(black_box(
        values
            .into_iter()
            .fold(input.seed, |result, v| result.rotate_left(5) ^ v),
    ))
}

pub fn memory_scan(input: MemoryScan) -> Result<u64, BoxError> {
    input.validate()?;
    let mut values: Vec<u8> = (0..input.working_set_bytes)
        .map(|i| input.seed.wrapping_add(i as u64) as u8)
        .collect();
    let mut result = input.seed;
    for pass in 0..input.passes {
        for value in black_box(&mut values) {
            *value = value.wrapping_add(pass.wrapping_add(1) as u8);
            result = result.rotate_left(5) ^ *value as u64;
        }
    }
    Ok(black_box(result))
}

pub fn matrix(input: Matrix) -> Result<u64, BoxError> {
    input.validate()?;
    let dim = input.dimension;
    let mut left: Vec<u64> = (0..dim * dim)
        .map(|i| input.seed.wrapping_add(i as u64) & 65535)
        .collect();
    let right: Vec<u64> = (0..dim * dim)
        .map(|i| input.seed.rotate_left(17).wrapping_add(3 * i as u64) & 65535)
        .collect();
    let mut result = input.seed;
    for _ in 0..input.repetitions {
        let left_values = black_box(&mut left);
        for row in 0..dim {
            for col in 0..dim {
                let mut value = 0u64;
                for k in 0..dim {
                    value = value.wrapping_add(
                        left_values[row * dim + k].wrapping_mul(right[k * dim + col]),
                    );
                }
                result = result.rotate_left(5) ^ value;
            }
        }
        for value in left_values {
            *value = value.wrapping_add(1);
        }
    }
    Ok(black_box(result))
}

pub fn mixed(input: Mixed) -> Result<u64, BoxError> {
    let a = Arithmetic {
        iterations: input.arithmetic_iterations,
        seed: input.seed,
    };
    let s = Sorting {
        items: input.sort_items,
        passes: input.sort_passes,
        seed: input.seed,
    };
    let m = MemoryScan {
        working_set_bytes: input.working_set_bytes,
        passes: input.memory_passes,
        seed: input.seed,
    };
    let x = Matrix {
        dimension: input.matrix_dimension,
        repetitions: input.matrix_repetitions,
        seed: input.seed,
    };
    // Validate ALL components before any CPU work or allocation.
    a.validate()?;
    s.validate()?;
    m.validate()?;
    x.validate()?;
    Ok(black_box(
        arithmetic(a)?.rotate_left(5)
            ^ sorting(s)?.rotate_left(13)
            ^ memory_scan(m)?.rotate_left(29)
            ^ matrix(x)?,
    ))
}
