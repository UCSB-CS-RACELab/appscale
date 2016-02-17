import argparse
import random
import sys

def modify_line(line, col, multiplier, prob):
    fields = line.split()
    value = int(fields[col])
    if random.random() < prob:
        value *= multiplier
    result = ''
    for i in range(len(fields)):
        if result:
            result += ' '
        if i != col:
            result += fields[i]
        else:
            result += str(value)
    return result

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Harvests SDK call execution time information for an app.')
    parser.add_argument('--file', '-f', dest='file', default=None)
    parser.add_argument('--column', '-c', type=int, dest='column', default=-1)
    parser.add_argument('--multiplier', '-m', type=int, dest='multiplier')
    parser.add_argument('--probability', '-p', type=float, dest='probability', default=1.0)
    args = parser.parse_args()

    if not args.file:
        print 'File name argument is required'
        sys.exit(1)
    if args.column < 0:
        print 'A non-negative column value is required'
        sys.exit(1)
    if not args.multiplier:
        print 'A multiplier value is required'
        sys.exit(1)
    if args.probability < 0 or args.probability > 1:
        print 'Proability value must be in the interval [0,1]'
        sys.exit(1)

    fp = open(args.file, 'r')
    lines = fp.readlines()
    fp.close()

    print lines[0],
    for i in range(1, len(lines)):
        print modify_line(lines[i].strip(), args.column, args.multiplier, args.probability)
